package vip.mseapp.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.ImCrypt;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImInstanceConfig;
import vip.mseapp.im.mapper.ImInstanceConfigMapper;
import vip.mseapp.im.support.ImInstanceResolver;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * IM 开通平台编排执行器（P2）：以本工程 docker/openim 裁剪版 compose 为模板，按租户
 * 渲染独立工作目录（compose 去 container_name、.env 随机凭据+端口块），经 docker CLI 起栈，
 * 健康等待以 get_admin_token 实测为准，成功后写 PLATFORM_HOSTED 台账行（runAs 租户世界落库）。
 *
 * <p>端口块：租户 n 的 api = portBase + n*blockSize，ws/minio/console/livekit/rtc-tcp 依次 +1..+5。
 * 生命周期：provision（含 start）/stop/start/destroy（down -v 并删台账行）。
 */
@Service
public class ImProvisionService {

    private static final Logger log = LoggerFactory.getLogger(ImProvisionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MseImProperties properties;
    private final ImInstanceConfigMapper instanceMapper;
    private final ImCrypt imCrypt;
    private final OpenImClient openImClient;
    private final ImInstanceResolver instanceResolver;

    public ImProvisionService(MseImProperties properties, ImInstanceConfigMapper instanceMapper,
                              ImCrypt imCrypt, OpenImClient openImClient,
                              ImInstanceResolver instanceResolver) {
        this.properties = properties;
        this.instanceMapper = instanceMapper;
        this.imCrypt = imCrypt;
        this.openImClient = openImClient;
        this.instanceResolver = instanceResolver;
    }

    /** 开通：清孤儿工作目录 → 渲染 → 起栈 → 健康等待 → 写台账（已有租户自助行时拒绝，避免覆盖租户自建实例） */
    public Map<String, Object> provision(Long tenantId) {
        requireEnabled(tenantId);
        ImInstanceConfig existing = rowOf(tenantId);
        if (existing != null) {
            throw new ServiceException(409, "该租户已存在 IM 实例配置（" + existing.getSource() + "），不可重复开通");
        }
        int[] ports = allocatePorts();
        String secret = randomHex(24);
        // callbackToken 前移生成：renderCompose 渲染 webhooks.yml 回调地址需要它（台账落库时复用同一值）
        String callbackToken = randomHex(18);
        String[] publicUrls = publicUrls(tenantId, ports[0], ports[1]);
        Path dir = workDir(tenantId);
        if (Files.exists(dir)) {
            // 孤儿工作目录（上次开通失败残留）：mongo 等数据卷是 bind mount，down -v 不清，
            // 残留数据与新 .env 随机凭据必然失配——整目录重装
            try {
                compose(tenantId, dir, "down", "-v");
            } catch (Exception e) {
                log.warn("[mse-im] 孤儿栈清理失败（继续重装）: {}", e.getMessage());
            }
            deleteRecursively(dir);
        }
        renderCompose(dir, tenantId, callbackToken);
        renderEnv(dir, tenantId, ports, secret);
        writeNginxConf(dir, tenantId, ports);
        java.util.concurrent.atomic.AtomicReference<ImInstanceConfig> inserted = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            compose(tenantId, dir, "up", "-d");
            awaitHealthy(ports[0], secret);
            // 台账写入纳入清场范围：插入失败同样回收栈与工作目录，不留孤儿
            inserted.set(TenantContext.runAs(tenantId, () -> {
                ImInstanceConfig instance = new ImInstanceConfig();
                instance.setTenantId(tenantId);
                instance.setSource(ImInstanceResolver.SOURCE_PLATFORM_HOSTED);
                instance.setApiUrl("http://127.0.0.1:" + ports[0]);
                instance.setWsUrl("ws://127.0.0.1:" + ports[1]);
                if (publicUrls.length > 0) {
                    instance.setPublicApiUrl(publicUrls[0]);
                    instance.setPublicWsUrl(publicUrls[1]);
                }
                // LiveKit 信令地址：域名模板形态走 wss 子域 /rtc 反代；否则写实例端口内网坐标
                instance.setLivekitUrl(publicUrls.length > 0
                        ? "wss://" + properties.getProvision().getDomainTemplate().replace("{n}", String.valueOf(tenantId)) + "/rtc"
                        : "ws://127.0.0.1:" + ports[4]);
                instance.setAdminUserId("imAdmin");
                instance.setSecretEnc(imCrypt.encrypt(secret));
                instance.setCallbackToken(callbackToken);
                instance.setStatus(0);
                instanceMapper.insert(instance);
                return instance;
            }));
        } catch (RuntimeException e) {
            // 失败清场：残留半启动栈与随机凭据数据卷对重试毫无价值
            try {
                compose(tenantId, dir, "down", "-v");
                deleteRecursively(dir);
            } catch (Exception ignored) {
                // 清场失败保留现场供人工排查
            }
            throw e;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("apiPort", ports[0]);
        out.put("wsPort", ports[1]);
        out.put("livekitPort", ports[4]);
        out.put("projectId", projectName(tenantId));
        out.put("callbackToken", inserted.get().getCallbackToken());
        if (publicUrls.length > 0) {
            out.put("publicApiUrl", publicUrls[0]);
            out.put("publicWsUrl", publicUrls[1]);
            out.put("nginxConf", nginxConf(tenantId));
        }
        return out;
    }

    /** 停栈（保留数据与台账；status 置停用，客户端立即报未开通） */
    public void stop(Long tenantId) {
        requireEnabled(tenantId);
        ImInstanceConfig row = requirePlatformRow(tenantId);
        compose(tenantId, workDir(tenantId), "stop");
        TenantContext.runAs(tenantId, () -> {
            row.setStatus(1);
            instanceMapper.updateById(row);
            return null;
        });
    }

    /** 启动已开通的栈（health 不再等待，跑栈后台自愈） */
    public void start(Long tenantId) {
        requireEnabled(tenantId);
        requirePlatformRow(tenantId);
        compose(tenantId, workDir(tenantId), "start");
        TenantContext.runAs(tenantId, () -> {
            ImInstanceConfig row = rowOf(tenantId);
            if (row != null) {
                row.setStatus(0);
                instanceMapper.updateById(row);
            }
            return null;
        });
    }

    /** 销毁：down -v（含数据卷）+ 物理删台账行——不可逆，destroy 前调用方自行确认。
     *  物理删除：逻辑删除会在 (tenant_id, deleted) 唯一键上随销毁次数累积，堵死重新开通 */
    public void destroy(Long tenantId) {
        requireEnabled(tenantId);
        requirePlatformRow(tenantId);
        compose(tenantId, workDir(tenantId), "down", "-v");
        TenantContext.runAs(tenantId, () -> {
            ImInstanceConfig row = rowOf(tenantId);
            if (row != null) {
                instanceMapper.deletePhysicallyById(row.getId());
            }
            return null;
        });
    }

    /** 状态：台账行 + get_admin_token 实测探活（探活须 runAs 租户世界，平台会话读不到租户台账） */
    public Map<String, Object> status(Long tenantId) {
        requireEnabled(tenantId);
        ImInstanceConfig row = requirePlatformRow(tenantId);
        Boolean alive = TenantContext.runAs(tenantId, () -> {
            try {
                openImClient.adminToken(instanceResolver.resolve(tenantId));
                return Boolean.TRUE;
            } catch (Exception e) {
                return Boolean.FALSE;
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("status", row.getStatus());
        out.put("alive", alive);
        out.put("apiUrl", row.getApiUrl());
        out.put("lastTestOk", row.getLastTestOk());
        out.put("lastTestMsg", row.getLastTestMsg());
        return out;
    }

    // -------------------- 内部实现 --------------------

    private void awaitHealthy(int apiPort, String secret) {
        MseImProperties.Server probe = new MseImProperties.Server();
        probe.setApiUrl("http://127.0.0.1:" + apiPort);
        probe.setSecret(secret);
        probe.setAdminUserId("imAdmin");
        long deadline = System.currentTimeMillis()
                + properties.getProvision().getHealthTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                openImClient.adminToken(probe);
                return;
            } catch (Exception e) {
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ServiceException(500, "开通健康等待被中断");
                }
            }
        }
        throw new ServiceException(504, "实例健康等待超时（" + properties.getProvision().getHealthTimeoutSeconds() + "s），请检查 docker 日志后重试 start 或 destroy");
    }

    private void renderCompose(Path dir, Long tenantId, String callbackToken) {
        Path template = Path.of(properties.getProvision().getTemplateDir(), "docker-compose.yaml");
        List<String> lines;
        try {
            lines = Files.readAllLines(template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException(500, "读取 compose 模板失败（mse.im.provision.template-dir）：" + template);
        }
        List<String> out = new java.util.ArrayList<>(lines.size());
        for (String line : lines) {
            String trimmed = line.strip();
            boolean strip = trimmed.startsWith("container_name:")
                    || trimmed.startsWith("- \"12379:2379\"") || trimmed.startsWith("- \"12380:2380\"");
            if (!strip) {
                out.add(line);
                continue;
            }
            // 剥离项紧跟裸 ports: 键时把键改写为空数组（ports: 后无条目会被 compose 判非法）
            if (!out.isEmpty() && out.get(out.size() - 1).strip().equals("ports:")) {
                out.set(out.size() - 1, out.get(out.size() - 1).replaceFirst("ports:\\s*$", "ports: []"));
            }
            out.add("# [provision stripped] " + trimmed);
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("docker-compose.yaml"), String.join("\n", out), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException(500, "写入实例 compose 失败：" + dir);
        }
        // 模板的单文件挂载（config/ 下被 compose 引用的覆盖配置：openim-rpc-auth.yml、webhooks.yml 等）
        // 以项目目录为相对根，渲染时整目录复制——只复制单个文件会在模板新增挂载时漏带，
        // 缺失路径会被 Docker 建为目录，容器启动即报「mount a directory onto a file」。
        // webhooks.yml 例外：回调地址含租户专属段（/im/callback/{tenantId}/{token}），模板里是
        // 开发期静态值，按当前租户渲染替换（token 即上方前移生成的 callbackToken，与台账一致），
        // 否则 OpenIM 事件回调路由到不存在的租户段被 mse 端 404，消息审计/机器人代发全链失效
        Path cfgSrcDir = Path.of(properties.getProvision().getTemplateDir(), "config");
        if (Files.isDirectory(cfgSrcDir)) {
            try (var files = Files.list(cfgSrcDir)) {
                Files.createDirectories(dir.resolve("config"));
                for (Path src : files.filter(Files::isRegularFile).toList()) {
                    Path dst = dir.resolve("config").resolve(src.getFileName().toString());
                    if ("webhooks.yml".equals(src.getFileName().toString())) {
                        renderWebhooks(src, dst, tenantId, callbackToken);
                    } else {
                        Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException e) {
                throw new ServiceException(500, "复制模板覆盖配置目录失败：" + cfgSrcDir);
            }
        }
    }

    /** webhooks.yml 渲染：替换回调 URL 的 {tenantId}/{token} 段（保留 host.docker.internal 容器→宿主坐标） */
    private void renderWebhooks(Path src, Path dst, Long tenantId, String callbackToken) {
        try {
            List<String> lines = Files.readAllLines(src, StandardCharsets.UTF_8);
            List<String> out = new java.util.ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.strip().startsWith("url:")) {
                    out.add(line.replaceFirst("(im/callback/)\\d+(/[0-9a-f]+)",
                            "$1" + tenantId + "/" + callbackToken));
                } else {
                    out.add(line);
                }
            }
            Files.writeString(dst, String.join("\n", out), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException(500, "渲染 webhooks 回调配置失败：" + src);
        }
    }

    private void renderEnv(Path dir, Long tenantId, int[] ports, String secret) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("MONGO_IMAGE", "mongo:7.0.28");
        env.put("REDIS_IMAGE", "redis:7-alpine");
        env.put("KAFKA_IMAGE", "bitnamilegacy/kafka:3.5.1");
        env.put("MINIO_IMAGE", "minio/minio:RELEASE.2024-05-28T17-19-04Z");
        env.put("ETCD_IMAGE", "bitnamilegacy/etcd:3.5.13");
        env.put("OPENIM_SERVER_IMAGE", "openim/openim-server:v3.8.3-patch.15");
        env.put("DATA_DIR", "./data");
        env.put("MONGO_ADDRESS", "im-mongo:27017");
        env.put("MONGO_USERNAME", "openIM");
        env.put("MONGO_PASSWORD", randomHex(12));
        env.put("KAFKA_ADDRESS", "im-kafka:9094");
        env.put("ETCD_ADDRESS", "im-etcd:2379");
        env.put("REDIS_ADDRESS", "im-redis:6379");
        env.put("REDIS_PASSWORD", randomHex(12));
        env.put("MINIO_INTERNAL_ADDRESS", "im-minio:9000");
        env.put("MINIO_EXTERNAL_ADDRESS", "http://127.0.0.1:" + ports[2]);
        env.put("MINIO_ACCESS_KEY_ID", "root");
        env.put("MINIO_SECRET_ACCESS_KEY", randomHex(12));
        env.put("MINIO_PORT", String.valueOf(ports[2]));
        env.put("MINIO_CONSOLE_PORT", String.valueOf(ports[3]));
        env.put("OPENIM_SECRET", secret);
        env.put("OPENIM_API_PORT", String.valueOf(ports[0]));
        env.put("OPENIM_MSG_GATEWAY_PORT", String.valueOf(ports[1]));
        env.put("LOG_IS_STDOUT", "true");
        env.put("LOG_LEVEL", "3");
        // LiveKit（音视频通话）：key/secret 用 mse 全局配置（mse-im 据此签 token，双端一致才能进房）
        MseImProperties.Livekit livekit = properties.getLivekit();
        env.put("LIVEKIT_IMAGE", "livekit/livekit-server:latest");
        env.put("LIVEKIT_NODE_IP", "127.0.0.1");
        env.put("LIVEKIT_API_KEY", livekit.getApiKey() == null || livekit.getApiKey().isBlank() ? "mse-devkey" : livekit.getApiKey());
        env.put("LIVEKIT_API_SECRET", livekit.getApiSecret() == null || livekit.getApiSecret().isBlank() ? randomHex(24) : livekit.getApiSecret());
        env.put("LIVEKIT_PORT", String.valueOf(ports[4]));
        env.put("LIVEKIT_RTC_TCP_PORT", String.valueOf(ports[5]));
        env.put("LIVEKIT_UDP_START", String.valueOf(ports[4]));
        env.put("LIVEKIT_UDP_END", String.valueOf(ports[4] + 9));
        StringBuilder text = new StringBuilder("# generated by mse-im provision for tenant " + tenantId + "\n");
        env.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
        try {
            Files.writeString(dir.resolve(".env"), text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException(500, "写入实例 .env 失败：" + dir);
        }
    }

    /** 升级：换 OPENIM_SERVER_IMAGE 镜像 tag 滚动重建 openim-server 容器（中间件不动），健康等待 */
    public Map<String, Object> upgrade(Long tenantId, String imageTag) {
        requireEnabled(tenantId);
        ImInstanceConfig row = requirePlatformRow(tenantId);
        if (imageTag == null || imageTag.isBlank() || !imageTag.matches("[A-Za-z0-9._-]+")) {
            throw new ServiceException(400, "非法镜像 tag");
        }
        Path envFile = workDir(tenantId).resolve(".env");
        try {
            List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
            List<String> out = new java.util.ArrayList<>(lines.size());
            boolean replaced = false;
            for (String line : lines) {
                if (line.startsWith("OPENIM_SERVER_IMAGE=")) {
                    out.add("OPENIM_SERVER_IMAGE=openim/openim-server:" + imageTag);
                    replaced = true;
                } else {
                    out.add(line);
                }
            }
            if (!replaced) {
                throw new ServiceException(500, ".env 缺少 OPENIM_SERVER_IMAGE 行");
            }
            Files.write(envFile, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException(500, "读取/写入 .env 失败：" + envFile);
        }
        int apiPort = parsePort(row.getApiUrl());
        compose(tenantId, workDir(tenantId), "up", "-d");
        awaitHealthy(apiPort, imCrypt.decrypt(row.getSecretEnc()));
        TenantContext.runAs(tenantId, () -> {
            row.setLastTestTime(java.time.LocalDateTime.now());
            row.setLastTestOk(0);
            row.setLastTestMsg("升级至 " + imageTag + " 成功");
            instanceMapper.updateById(row);
            return null;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("image", "openim/openim-server:" + imageTag);
        out.put("apiPort", apiPort);
        return out;
    }

    /** 备份：mongo archive（docker exec mongodump）+ minio 数据卷 tar，落 backup 目录并返回路径 */
    public Map<String, Object> backup(Long tenantId) {
        requireEnabled(tenantId);
        requirePlatformRow(tenantId);
        Path dir = workDir(tenantId);
        Path backupRoot = Path.of(properties.getProvision().getBackupDir());
        String stamp = String.valueOf(System.currentTimeMillis());
        try {
            Files.createDirectories(backupRoot);
        } catch (IOException e) {
            throw new ServiceException(500, "备份目录创建失败：" + backupRoot);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mongo", backupMongo(tenantId, dir, backupRoot, stamp));
        out.put("minio", backupMinio(tenantId, dir, backupRoot, stamp));
        return out;
    }

    private String backupMongo(Long tenantId, Path dir, Path backupRoot, String stamp) {
        String container = composeContainerName(tenantId, "im-mongo");
        Path target = backupRoot.resolve(projectName(tenantId) + "-mongo-" + stamp + ".archive");
        // mongo root 口令在容器内 env（MONGO_INITDB_ROOT_PASSWORD，来自实例 .env），容器内组装 URI
        List<String> command = List.of("docker", "exec", container, "sh", "-c",
                "mongodump --uri=\"mongodb://root:$MONGO_INITDB_ROOT_PASSWORD@127.0.0.1:27017/admin\" --archive");
        execToFile(command, target);
        return target.toString();
    }

    private String backupMinio(Long tenantId, Path dir, Path backupRoot, String stamp) {
        Path target = backupRoot.resolve(projectName(tenantId) + "-minio-" + stamp + ".tar.gz");
        Path dataDir = dir.resolve("data").resolve("components").resolve("mnt");
        if (!Files.exists(dataDir)) {
            return ""; // minio 卷未产生数据
        }
        List<String> command = List.of("tar", "czf", target.toAbsolutePath().toString(),
                "-C", dataDir.toAbsolutePath().toString(), ".");
        execRaw(command);
        return target.toString();
    }

    private void execToFile(List<String> command, Path target) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            String stderr;
            try (var in = process.getInputStream(); var out = Files.newOutputStream(target); var err = process.getErrorStream()) {
                in.transferTo(out);
                // stderr 并行消费防管道满阻塞，失败时随异常带出定因
                stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(30, TimeUnit.MINUTES);
            if (!finished || process.exitValue() != 0) {
                throw new ServiceException(500, "备份命令失败：" + command.get(0) + "（exit="
                        + (finished ? process.exitValue() : "timeout") + "）"
                        + (stderr.isBlank() ? "" : " " + stderr.substring(0, Math.min(stderr.length(), 300))));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(500, "备份被中断");
        } catch (IOException e) {
            throw new ServiceException(500, "备份执行失败：" + e.getMessage());
        }
    }

    private void execRaw(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            boolean finished = process.waitFor(30, TimeUnit.MINUTES);
            if (!finished || process.exitValue() != 0) {
                throw new ServiceException(500, "命令执行失败：" + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(500, "命令执行被中断");
        } catch (IOException e) {
            throw new ServiceException(500, "命令不可用：" + e.getMessage());
        }
    }

    /** compose 项目内服务容器名（container_name 已被渲染剥离，取 compose 默认命名） */
    private String composeContainerName(Long tenantId, String service) {
        List<String> command = List.of("docker", "compose",
                "--project-name", projectName(tenantId),
                "--project-directory", workDir(tenantId).toAbsolutePath().toString(),
                "-f", workDir(tenantId).resolve("docker-compose.yaml").toAbsolutePath().toString(),
                "ps", "-q", service);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            process.waitFor(1, TimeUnit.MINUTES);
            if (output.isBlank()) {
                throw new ServiceException(404, "实例容器不存在（服务 " + service + "）");
            }
            return output.lines().findFirst().orElseThrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(500, "查询容器被中断");
        } catch (IOException e) {
            throw new ServiceException(500, "docker CLI 不可用：" + e.getMessage());
        }
    }

    /** 公网坐标：配置了子域模板（占位 {n}=租户编号）用 https/wss 子域；未配置返回空数组（台账留空 → 解析回落内网值） */
    private String[] publicUrls(Long tenantId, int apiPort, int wsPort) {
        String template = properties.getProvision().getDomainTemplate();
        if (template == null || template.isBlank()) {
            return new String[0];
        }
        String host = template.replace("{n}", String.valueOf(tenantId));
        return new String[]{"https://" + host + "/api", "wss://" + host + "/msg_gateway"};
    }

    /** 生成实例的 nginx 同域反代配置段（server 块；TLS 证书路径由部署方按域名补齐） */
    public String nginxConf(Long tenantId) {
        requireEnabled(tenantId);
        ImInstanceConfig row = requirePlatformRow(tenantId);
        int apiPort = parsePort(row.getApiUrl());
        int wsPort = parsePort(row.getWsUrl());
        String publicApi = row.getPublicApiUrl() == null || row.getPublicApiUrl().isBlank()
                ? "http://127.0.0.1:" + apiPort : row.getPublicApiUrl();
        String host = URI.create(publicApi).getHost() == null ? "im.tenant.local" : URI.create(publicApi).getHost();
        return """
                # mse-im 生成（租户 %s）——同域反代：/api → openim-api，/msg_gateway → msggateway(WSS)，mse 路径转网关
                # HTTPS 证书路径请按实际域名补齐；CLIENT 部分地址即客户端「服务器地址」
                server {
                    listen 443 ssl;
                    server_name %s;
                    # ssl_certificate     /etc/nginx/certs/%s.pem;
                    # ssl_certificate_key /etc/nginx/certs/%s.key;

                    location /api/        { proxy_pass http://127.0.0.1:%d/; }
                    location /msg_gateway { proxy_pass http://127.0.0.1:%d; proxy_http_version 1.1;
                                            proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; }
                    location /auth/       { proxy_pass http://127.0.0.1:48080; }
                    location /system/     { proxy_pass http://127.0.0.1:48080; }
                    location /im/         { proxy_pass http://127.0.0.1:48080; }
                }
                """.formatted(tenantId, host, host, host, apiPort, wsPort);
    }

    /** 生成并落盘实例 nginx 配置段（开通时调用，工作目录内留档） */
    private void writeNginxConf(Path dir, Long tenantId, int[] ports) {
        if (properties.getProvision().getDomainTemplate() == null
                || properties.getProvision().getDomainTemplate().isBlank()) {
            return; // 未配置子域时公网坐标为本机端口直连，无需反代配置
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("nginx.conf"), nginxConfText(tenantId, ports), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException(500, "写入 nginx 配置失败：" + dir);
        }
    }

    private String nginxConfText(Long tenantId, int[] ports) {
        String host = properties.getProvision().getDomainTemplate().replace("{n}", String.valueOf(tenantId));
        return """
                # mse-im 生成（租户 %s）——同域反代：/api → openim-api，/msg_gateway → msggateway(WSS)，mse 路径转网关
                # HTTPS 证书路径请按实际域名补齐
                server {
                    listen 443 ssl;
                    server_name %s;
                    # ssl_certificate     /etc/nginx/certs/%s.pem;
                    # ssl_certificate_key /etc/nginx/certs/%s.key;

                    location /api/        { proxy_pass http://127.0.0.1:%d/; }
                    location /msg_gateway { proxy_pass http://127.0.0.1:%d; proxy_http_version 1.1;
                                            proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; }
                    location /auth/       { proxy_pass http://127.0.0.1:48080; }
                    location /system/     { proxy_pass http://127.0.0.1:48080; }
                    location /im/         { proxy_pass http://127.0.0.1:48080; }
                }
                """.formatted(tenantId, host, host, host, ports[0], ports[1]);
    }

    private void compose(Long tenantId, Path dir, String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("docker");
        command.add("compose");
        command.add("--project-name");
        command.add(projectName(tenantId));
        command.add("--project-directory");
        command.add(dir.toAbsolutePath().toString());
        command.add("-f");
        command.add(dir.resolve("docker-compose.yaml").toAbsolutePath().toString());
        command.addAll(List.of(args));
        log.info("[mse-im] 编排执行: {}", command);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new ServiceException(504, "docker compose 执行超时");
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new ServiceException(500, "docker compose 执行失败：" + abbreviate(output));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(500, "docker compose 执行被中断");
        } catch (IOException e) {
            throw new ServiceException(500, "docker CLI 不可用（确认宿主机已安装 docker 且服务账号有权限）：" + e.getMessage());
        }
    }

    private void requireEnabled(Long tenantId) {
        if (!properties.isEnabled()) {
            throw new ServiceException(503, "IM 功能未开通");
        }
        if (!properties.getProvision().isEnabled()) {
            throw new ServiceException(503, "IM 开通平台未启用（mse.im.provision.enabled）");
        }
        if (tenantId == null || tenantId <= 0) {
            throw new ServiceException(400, "非法租户编号");
        }
    }

    private ImInstanceConfig requirePlatformRow(Long tenantId) {
        ImInstanceConfig row = rowOf(tenantId);
        if (row == null || !ImInstanceResolver.SOURCE_PLATFORM_HOSTED.equals(row.getSource())) {
            throw new ServiceException(404, "该租户无平台托管实例");
        }
        return row;
    }

    /** 台账行读取：编排端点运行在平台会话（tenant_id=0，路由落平台库），而台账是租户世界
     *  的 master 通道表——必须 runAs 租户世界读取/落库（独立库租户亦由此正确路由） */
    private ImInstanceConfig rowOf(Long tenantId) {
        return TenantContext.runAs(tenantId, () -> instanceMapper.selectOne(
                new LambdaQueryWrapper<ImInstanceConfig>()
                        .eq(ImInstanceConfig::getTenantId, tenantId)
                        .last("limit 1")));
    }

    /**
     * 端口块分配：扫既有平台托管实例 api_url 里的端口，取最大已用端口后按 block-size
     * 对齐到下一个空闲块（每租户一族端口，族内 api/ws/minio/console 连续；
     * 租户编号是雪花大数不可参与端口运算）；首开 = portBase。
     * 全局扫描运行在平台会话（无租户上下文）：runIgnore 落主库——独立库租户的台账行
     * 不在主库，端口分配仅统计共享库侧（独立库托管实例请人工核对端口占用后手工登记）。
     */
    private int[] allocatePorts() {
        int portBase = properties.getProvision().getPortBase();
        int blockSize = Math.max(1, properties.getProvision().getBlockSize());
        int maxUsed = portBase - 1;
        List<ImInstanceConfig> rows = TenantContext.runIgnore(() -> instanceMapper.selectList(
                new LambdaQueryWrapper<ImInstanceConfig>()
                        .eq(ImInstanceConfig::getSource, ImInstanceResolver.SOURCE_PLATFORM_HOSTED)));
        for (ImInstanceConfig row : rows) {
            int port = parsePort(row.getApiUrl());
            if (port > maxUsed) {
                maxUsed = port;
            }
        }
        int base = portBase + ((maxUsed + 1 - portBase) + blockSize - 1) / blockSize * blockSize;
        // 6 端口：api / ws / minio / minio-console / livekit 信令 / livekit rtc-tcp
        // （livekit UDP 段复用 livekit 信令端口起 10 个——TCP/UDP 端口空间独立，块内不冲突）
        return new int[]{base, base + 1, base + 2, base + 3, base + 4, base + 5};
    }

    private static int parsePort(String url) {
        if (url == null) {
            return -1;
        }
        int slash = url.indexOf("//");
        int from = slash < 0 ? 0 : slash + 2;
        int colon = url.indexOf(':', from);
        if (colon < 0) {
            return -1;
        }
        int end = colon + 1;
        while (end < url.length() && Character.isDigit(url.charAt(end))) {
            end++;
        }
        try {
            return Integer.parseInt(url.substring(colon + 1, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Path workDir(Long tenantId) {
        return Path.of(properties.getProvision().getDataRoot(), "tenant-" + tenantId);
    }

    private static String projectName(Long tenantId) {
        return "im-tenant-" + tenantId;
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** 递归删除实例工作目录（compose/.env/bind-mount 数据卷） */
    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // 占用文件跳过，残留由下次开通的孤儿清理兜底
                }
            });
        } catch (IOException e) {
            log.warn("[mse-im] 工作目录删除失败: {} err={}", dir, e.getMessage());
        }
    }

    private static String abbreviate(String text) {
        text = text == null ? "" : text.trim();
        return text.length() > 400 ? text.substring(0, 400) + "..." : text;
    }
}
