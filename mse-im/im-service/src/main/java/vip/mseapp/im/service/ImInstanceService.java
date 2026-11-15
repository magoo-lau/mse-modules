package vip.mseapp.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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
import vip.mseapp.im.support.ImUrlValidator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 租户自助实例台账（每租户一行）：查询脱敏 / 保存（secret 留空不变） / 连通性测试。
 *
 * <p>来源语义：租户侧保存把行固定为 TENANT_SELF_HOSTED；对 PLATFORM_HOSTED 行，
 * 租户可启停但不可改地址/密钥（托管实例坐标由平台编排维护）。
 * api-url 经 SSRF 校验（租户录入场景）；callback_token 是回调 URL 的机密段，
 * 回调地址形如 /im/callback/{tenantId}/{token}/{command}——租户段显式路由到租户库查台账，
 * 无「token→租户」间接映射（独立库租户的行不在主库，间接映射一旦漂移/丢失回调即全部失效）。
 */
@Service
public class ImInstanceService {

    private static final Logger log = LoggerFactory.getLogger(ImInstanceService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MseImProperties properties;
    private final ImInstanceConfigMapper instanceMapper;
    private final ImCrypt imCrypt;
    private final ImUrlValidator urlValidator;
    private final ImInstanceResolver instanceResolver;
    private final OpenImClient openImClient;

    /** webhook 探测专用短超时客户端（探测失败不影响连通性结论） */
    private final HttpClient probeHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public ImInstanceService(MseImProperties properties, ImInstanceConfigMapper instanceMapper, ImCrypt imCrypt,
                             ImUrlValidator urlValidator, ImInstanceResolver instanceResolver,
                             OpenImClient openImClient) {
        this.properties = properties;
        this.instanceMapper = instanceMapper;
        this.imCrypt = imCrypt;
        this.urlValidator = urlValidator;
        this.instanceResolver = instanceResolver;
        this.openImClient = openImClient;
    }

    /** 当前租户台账行（无行返回 null，前端展示为「未配置，走平台默认」） */
    public ImInstanceConfig getOwn() {
        ImInstanceConfig row = currentRow();
        return row == null ? null : mask(row);
    }

    /** 当前租户的 webhook 接收地址（callback.baseUrl + /im/callback/{tenantId}/{token}） */
    public String callbackUrl() {
        ImInstanceConfig row = currentRow();
        if (row == null || row.getCallbackToken() == null || row.getCallbackToken().isBlank()) {
            return null;
        }
        return properties.getCallback().getBaseUrl() + "/im/callback/" + row.getTenantId()
                + "/" + row.getCallbackToken();
    }

    /**
     * 保存本租户实例配置（insert or update；secret 留空 = 不变）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImInstanceConfig saveOwn(ImInstanceSaveRequest request) {
        Long tenantId = TenantContext.getRequiredTenantId();
        urlValidator.validateApiUrl(request.apiUrl());
        if (request.wsUrl() != null && !request.wsUrl().isBlank()) {
            urlValidator.validateWsUrl(request.wsUrl());
        }
        ImInstanceConfig row = currentRow();
        boolean creating = row == null;
        if (creating) {
            row = new ImInstanceConfig();
            row.setTenantId(tenantId);
            row.setSource(ImInstanceResolver.SOURCE_SELF_HOSTED);
            row.setStatus(0);
        } else if (ImInstanceResolver.SOURCE_PLATFORM_HOSTED.equals(row.getSource())
                && (changed(row.getApiUrl(), request.apiUrl()) || changed(row.getPublicApiUrl(), request.publicApiUrl())
                || changed(row.getPublicWsUrl(), request.publicWsUrl()) || changed(row.getWsUrl(), request.wsUrl())
                || changed(row.getAdminUserId(), request.adminUserId()) || changed(row.getLivekitUrl(), request.livekitUrl())
                || (request.secret() != null && !request.secret().isBlank()))) {
            throw new ServiceException(403, "平台托管实例的地址与密钥由平台维护，租户仅可启停");
        }
        row.setSource(ImInstanceResolver.SOURCE_SELF_HOSTED.equals(row.getSource()) || creating
                ? ImInstanceResolver.SOURCE_SELF_HOSTED : row.getSource());
        row.setApiUrl(request.apiUrl());
        row.setWsUrl(request.wsUrl());
        row.setPublicApiUrl(request.publicApiUrl());
        row.setPublicWsUrl(request.publicWsUrl());
        row.setWebBaseUrl(request.webBaseUrl());
        row.setLivekitUrl(request.livekitUrl());
        row.setAdminUserId(request.adminUserId() == null || request.adminUserId().isBlank()
                ? "imAdmin" : request.adminUserId());
        if (request.secret() != null && !request.secret().isBlank()) {
            row.setSecretEnc(imCrypt.encrypt(request.secret()));
        }
        if (row.getSecretEnc() == null || row.getSecretEnc().isBlank()) {
            throw new ServiceException(400, "secret 不能为空");
        }
        if (row.getCallbackToken() == null || row.getCallbackToken().isBlank()) {
            row.setCallbackToken(newToken());
        }
        row.setStatus(request.status() == null ? 0 : request.status());
        if (creating) {
            instanceMapper.insert(row);
        } else {
            instanceMapper.updateById(row);
        }
        return mask(row);
    }

    /**
     * 连通性测试：以「台账行优先、否则 yaml 默认」的生效配置真实调一次 get_admin_token，
     * 结果回写台账行（无行不落库）。IM 可达后附带 webhook 回调可达性探测
     * （向 mse.im.callback.base-url 自端点发 mse.im.ping）——自备实例在 NAT 后时回调
     * 常不可达，机器人/停用踢线依赖回调，明示能力受限（方案风险 #9）。
     */
    public TestResult test() {
        Long tenantId = TenantContext.getRequiredTenantId();
        String message;
        boolean ok;
        try {
            MseImProperties.Server server = instanceResolver.resolve(tenantId);
            openImClient.adminToken(server);
            ok = true;
            message = "连通成功：" + server.getApiUrl() + webhookProbeSuffix(tenantId);
        } catch (Exception e) {
            ok = false;
            message = e.getMessage() == null ? "连通失败" : e.getMessage();
        }
        ImInstanceConfig row = currentRow();
        if (row != null) {
            row.setLastTestTime(LocalDateTime.now());
            row.setLastTestOk(ok ? 0 : 1);
            row.setLastTestMsg(message);
            instanceMapper.updateById(row);
        }
        return new TestResult(ok, message);
    }

    /** 回调入口按 tenantId+token 定位实例行：runAs 路由到租户库（独立库租户的台账行在自有库，
     *  主库无行）；租户已销毁（数据源路由失败）catch 返回 null，调用方走 404 拒绝 */
    public ImInstanceConfig findByTenantAndToken(Long tenantId, String token) {
        try {
            return TenantContext.runAs(tenantId, () -> instanceMapper.selectOne(
                    new LambdaQueryWrapper<ImInstanceConfig>()
                            .eq(ImInstanceConfig::getTenantId, tenantId)
                            .eq(ImInstanceConfig::getCallbackToken, token)
                            .last("limit 1")));
        } catch (Exception e) {
            log.warn("[mse-im] 回调定位租户失败（租户疑似已销毁）: tenantId={} err={}", tenantId, e.getMessage());
            return null;
        }
    }

    /** webhook 可达性探测：对回调基址自端点发 mse.im.ping（回调链路对机器人/踢线是硬依赖，
     *  自备实例 NAT 后常不可达——不可达仅附注提示，不影响连通性判定本身） */
    private String webhookProbeSuffix(Long tenantId) {
        ImInstanceConfig row = currentRow();
        if (row == null || row.getCallbackToken() == null || row.getCallbackToken().isBlank()) {
            return "；webhook 未配置（机器人/停用踢线不可用）";
        }
        String url = properties.getCallback().getBaseUrl() + "/im/callback/" + tenantId
                + "/" + row.getCallbackToken() + "/mse.im.ping";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"operationID\":\"probe\"}", StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = probeHttp.send(request, HttpResponse.BodyHandlers.ofString());
            // 全局异常处理器对 ServiceException 返回 HTTP 200 + {success:false} 信封，只看状态码会把
            // 「token 解析失败被拒」误报成可达——必须读响应体判成功语义
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() == 200 && !body.contains("\"success\":false")) {
                return "；webhook 回调可达";
            }
            if (body.contains("callback not found")) {
                return "；webhook 回调被拒（token 未在 mse 侧登记生效，请重存实例配置后重试）";
            }
            return "；webhook 回调探测异常（HTTP " + response.statusCode() + "）";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "；webhook 回调探测被中断";
        } catch (Exception e) {
            return "；webhook 回调不可达（自备实例位于内网/NAT 后属预期）：机器人/停用踢线能力受限";
        }
    }

    private ImInstanceConfig currentRow() {
        return instanceMapper.selectOne(new LambdaQueryWrapper<ImInstanceConfig>()
                .eq(ImInstanceConfig::getTenantId, TenantContext.getRequiredTenantId())
                .last("limit 1"));
    }

    /** 出参脱敏：secret 密文任何 HTTP 出口不回显 */
    private static ImInstanceConfig mask(ImInstanceConfig row) {
        row.setSecretEnc(null);
        return row;
    }

    private static boolean changed(String oldV, String newV) {
        String a = oldV == null ? "" : oldV;
        String b = newV == null ? "" : newV;
        return !a.equals(b);
    }

    private static String newToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 保存请求（secret 留空 = 保持不变；status 0 启用 1 停用；webBaseUrl/livekitUrl 空 = 回落全局缺省） */
    public record ImInstanceSaveRequest(String apiUrl, String wsUrl, String publicApiUrl, String publicWsUrl,
                                        String webBaseUrl, String livekitUrl, String adminUserId, String secret, Integer status) {
    }

    /** 连通性测试结果 */
    public record TestResult(boolean ok, String message) {
    }
}
