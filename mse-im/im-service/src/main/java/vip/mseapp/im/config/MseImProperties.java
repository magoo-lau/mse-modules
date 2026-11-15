package vip.mseapp.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IM 集成配置（mse.im.*）。
 *
 * <p>多租户绑多实例：台账 DB 行（im_instance_config）优先，其次 {@code servers[tenantId]}
 * 配置映射，未命中走 {@code server} 默认段；Nacos 热更新即增减实例。
 * crypt-key 为台账 secret 密文加密密钥；server.secret 与 OpenIM 实例 share.yml 顶层
 * secret 一致（生产必须更换并妥善保管）。
 */
@Component
@ConfigurationProperties(prefix = "mse.im")
public class MseImProperties {

    /** 总开关：false 时所有端点返回未开通（客户端据此隐藏聊天入口） */
    private boolean enabled = true;

    /** 实例 secret 密文加密密钥（生产必须修改；更换后历史密文不可解，需重新录入实例密钥） */
    private String cryptKey = "mse-im-default-crypt-key-please-change";

    /** 工作台跳转基址缺省值（聊天客户端免登跳转的 mse Web 端地址；租户可在「IM 设置」按行覆盖） */
    private String webBaseUrl = "http://localhost:3000";

    /** 默认实例（台账无行且未命中 servers 的租户走它；standalone/单实例只配这段） */
    private final Server server = new Server();

    /** 按租户预置的实例映射：key = 租户编号字符串（台账 DB 行优先于此） */
    private final Map<String, Server> servers = new LinkedHashMap<>();

    private final Sso sso = new Sso();

    private final Workbench workbench = new Workbench();

    /** 聊天客户端用户自设头像参数 */
    private final Avatar avatar = new Avatar();

    private final Client client = new Client();

    private final Ssrf ssrf = new Ssrf();

    private final Callback callback = new Callback();

    private final Provision provision = new Provision();

    /** 机器人参数（上下文感知回复：触发时从消息存档拉本会话最近聊天记录拼进提问） */
    private final Bot bot = new Bot();

    /** 音视频通话（LiveKit）：客户端信令经自研 custom 消息，媒体走 LiveKit 房间 */
    private final Livekit livekit = new Livekit();

    /** 一个 OpenIM 实例的连接坐标与凭证 */
    public static class Server {

        /** 内网 api 地址（mse-im → openim-api，admin REST） */
        private String apiUrl;

        /** 内网 WS 地址（预留连通性校验用，一般不直接下发） */
        private String wsUrl;

        /** 下发客户端的 apiAddr（同域名反代形态，如 https://im.xxx.com/api）；缺省回落 apiUrl */
        private String publicApiUrl;

        /** 下发客户端的 wsAddr（如 wss://im.xxx.com/msg_gateway）；缺省回落 wsUrl，再回落 apiUrl */
        private String publicWsUrl;

        /** 实例 share.yml 顶层 secret（内网敏感凭据，绝不进前端/仓库） */
        private String secret;

        /** 实例管理员账号（须在 share.yml 的 imAdminUserID 列表内） */
        private String adminUserId = "imAdmin";

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getWsUrl() {
            return wsUrl;
        }

        public void setWsUrl(String wsUrl) {
            this.wsUrl = wsUrl;
        }

        public String getPublicApiUrl() {
            return publicApiUrl;
        }

        public void setPublicApiUrl(String publicApiUrl) {
            this.publicApiUrl = publicApiUrl;
        }

        public String getPublicWsUrl() {
            return publicWsUrl;
        }

        public void setPublicWsUrl(String publicWsUrl) {
            this.publicWsUrl = publicWsUrl;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getAdminUserId() {
            return adminUserId;
        }

        public void setAdminUserId(String adminUserId) {
            this.adminUserId = adminUserId;
        }
    }

    /** 工作台 SSO 一次性 code 参数 */
    public static class Sso {

        /** code 有效期（秒） */
        private int codeTtlSeconds = 60;

        /** 同一用户 60 秒窗口内签发上限（点击时签发，30 次对人工点击绰绰有余且防刷） */
        private int issueRateLimit = 30;

        /** 浏览器不带 Origin 头时是否放行兑换（默认拒绝，防非浏览器环境扫描） */
        private boolean allowEmptyOrigin = false;

        public int getCodeTtlSeconds() {
            return codeTtlSeconds;
        }

        public void setCodeTtlSeconds(int codeTtlSeconds) {
            this.codeTtlSeconds = codeTtlSeconds;
        }

        public int getIssueRateLimit() {
            return issueRateLimit;
        }

        public void setIssueRateLimit(int issueRateLimit) {
            this.issueRateLimit = issueRateLimit;
        }

        public boolean isAllowEmptyOrigin() {
            return allowEmptyOrigin;
        }

        public void setAllowEmptyOrigin(boolean allowEmptyOrigin) {
            this.allowEmptyOrigin = allowEmptyOrigin;
        }
    }

    /** 工作台参数：图标存储 + 外部应用免登授权（OAuth2 授权码换 access_token） */
    public static class Workbench {

        /** 图标本地兜底目录（mse-fs 未引入/未部署时落盘位置） */
        private String iconDir = "./workbench-icons";

        /** 免登授权码有效期（秒）：跳转后对方前端需转交后端兑换，留足往返时间 */
        private int authCodeTtlSeconds = 300;

        /** access_token 有效期（秒）：对方服务端凭 token 调 /im/oauth/userinfo 识别用户 */
        private int accessTokenTtlSeconds = 7200;

        /** 同一用户 60 秒窗口内工作台跳转签发上限（外部应用入口点击时签发） */
        private int jumpRateLimit = 30;

        public String getIconDir() {
            return iconDir;
        }

        public void setIconDir(String iconDir) {
            this.iconDir = iconDir;
        }

        public int getAuthCodeTtlSeconds() {
            return authCodeTtlSeconds;
        }

        public void setAuthCodeTtlSeconds(int authCodeTtlSeconds) {
            this.authCodeTtlSeconds = authCodeTtlSeconds;
        }

        public int getAccessTokenTtlSeconds() {
            return accessTokenTtlSeconds;
        }

        public void setAccessTokenTtlSeconds(int accessTokenTtlSeconds) {
            this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        }

        public int getJumpRateLimit() {
            return jumpRateLimit;
        }

        public void setJumpRateLimit(int jumpRateLimit) {
            this.jumpRateLimit = jumpRateLimit;
        }
    }

    /** 聊天客户端用户自设头像参数：本地兜底目录（mse-fs 未引入/未部署时落盘位置；
     *  存储形态开关另经 mse.im.avatar.storage=auto/fs/local，由装配条件读取） */
    public static class Avatar {

        /** 头像本地兜底目录 */
        private String dir = "./im-avatar-assets";

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }

    /** OpenIM HTTP 客户端参数 */
    public static class Client {

        /** 单次调用超时（毫秒） */
        private int timeoutMs = 5000;

        /** admin token 临期刷新阈值（秒）：剩余有效期低于该值即重取 */
        private int adminTokenRefreshAheadSeconds = 600;

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getAdminTokenRefreshAheadSeconds() {
            return adminTokenRefreshAheadSeconds;
        }

        public void setAdminTokenRefreshAheadSeconds(int adminTokenRefreshAheadSeconds) {
            this.adminTokenRefreshAheadSeconds = adminTokenRefreshAheadSeconds;
        }
    }

    /** SSRF 加固（租户自助录入 api-url 时生效；平台开通实例的内部地址不经此校验） */
    public static class Ssrf {

        /** 放开的私有网段（IPv4 CIDR 或关键词 loopback）；默认空 = 内网/环回地址一律拒绝 */
        private List<String> allowPrivateCidrs = new ArrayList<>();

        public List<String> getAllowPrivateCidrs() {
            return allowPrivateCidrs;
        }

        public void setAllowPrivateCidrs(List<String> allowPrivateCidrs) {
            this.allowPrivateCidrs = allowPrivateCidrs;
        }
    }

    /** 机器人参数：上下文注入规模（群聊/单聊一致，审计表注入式，无 AI 侧会话状态） */
    public static class Bot {

        /** 触发后拉本会话最近消息条数拼进提问（0 = 关闭上下文注入，退回仅当前消息） */
        private int contextSize = 30;

        /** 上下文总字符上限（超出从最旧一条开始裁剪，保住头部说明与当前提问） */
        private int contextMaxChars = 8000;

        public int getContextSize() {
            return contextSize;
        }

        public void setContextSize(int contextSize) {
            this.contextSize = contextSize;
        }

        public int getContextMaxChars() {
            return contextMaxChars;
        }

        public void setContextMaxChars(int contextMaxChars) {
            this.contextMaxChars = contextMaxChars;
        }
    }

    /** webhook 回调参数 */
    public static class Callback {

        /** mse-im 对 OpenIM 实例可达的基址（回填给租户 webhooks.yml 的 url 前缀） */
        private String baseUrl = "http://127.0.0.1:8080";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /** 开通平台编排参数：enabled=false 时编排端点不可用 */
    public static class Provision {

        private boolean enabled = false;

        /** compose 模板目录（含 docker-compose.yaml，本工程 docker/openim 同款裁剪版） */
        private String templateDir = "docker/openim";

        /** 各实例工作目录根（compose + .env + 数据卷落位） */
        private String dataRoot = "./im-provision-data";

        /** 备份文件目录（mongo archive + minio 数据卷 tar） */
        private String backupDir = "./im-provision-backups";

        /** 公网子域模板（如 im-tenant-{n}.im.example.com；空 = 公网坐标按 127.0.0.1:端口），
         *  占位符 {n} = 租户编号；启用时台账 public url 与生成的 nginx 配置均使用该子域 */
        private String domainTemplate = "";

        /** 端口块基址：租户 n 的 api 端口 = portBase + n * blockSize */
        private int portBase = 11000;

        /** 每租户端口块大小 */
        private int blockSize = 10;

        /** 起栈后健康等待上限（秒） */
        private int healthTimeoutSeconds = 180;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTemplateDir() {
            return templateDir;
        }

        public void setTemplateDir(String templateDir) {
            this.templateDir = templateDir;
        }

        public String getDataRoot() {
            return dataRoot;
        }

        public void setDataRoot(String dataRoot) {
            this.dataRoot = dataRoot;
        }

        public String getBackupDir() {
            return backupDir;
        }

        public void setBackupDir(String backupDir) {
            this.backupDir = backupDir;
        }

        public String getDomainTemplate() {
            return domainTemplate;
        }

        public void setDomainTemplate(String domainTemplate) {
            this.domainTemplate = domainTemplate;
        }

        public int getPortBase() {
            return portBase;
        }

        public void setPortBase(int portBase) {
            this.portBase = portBase;
        }

        public int getBlockSize() {
            return blockSize;
        }

        public void setBlockSize(int blockSize) {
            this.blockSize = blockSize;
        }

        public int getHealthTimeoutSeconds() {
            return healthTimeoutSeconds;
        }

        public void setHealthTimeoutSeconds(int healthTimeoutSeconds) {
            this.healthTimeoutSeconds = healthTimeoutSeconds;
        }
    }

    /**
     * 音视频通话 RTC 参数（LiveKit）。全局单套 RTC（共享 RTC 集群形态）：
     * api-key/api-secret 与 LiveKit Server 配置一致；token 由 mse-im 手写 HS256 签发。
     * url 为信令地址（客户端直连，如 ws://127.0.0.1:7880 或 wss://im.xxx.com/rtc），
     * 台账行 livekit_url 优先（开通编排写入每租户实例端口）。
     */
    public static class Livekit {

        /** 总开关：false 时客户端隐藏通话入口、token 端点返回未开通 */
        private boolean enabled = false;

        /** LiveKit 信令地址（ws(s)://host:port） */
        private String url;

        /** API Key（LiveKit keys 配置段键名） */
        private String apiKey;

        /** API Secret（HS256 签名密钥，生产必须强随机且妥善保管） */
        private String apiSecret;

        /** token 有效期（秒）：覆盖呼叫+通话全程即可 */
        private int tokenTtlSeconds = 3600;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = apiSecret;
        }

        public int getTokenTtlSeconds() {
            return tokenTtlSeconds;
        }

        public void setTokenTtlSeconds(int tokenTtlSeconds) {
            this.tokenTtlSeconds = tokenTtlSeconds;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCryptKey() {
        return cryptKey;
    }

    public void setCryptKey(String cryptKey) {
        this.cryptKey = cryptKey;
    }

    public String getWebBaseUrl() {
        return webBaseUrl;
    }

    public void setWebBaseUrl(String webBaseUrl) {
        this.webBaseUrl = webBaseUrl;
    }

    public Server getServer() {
        return server;
    }

    public Map<String, Server> getServers() {
        return servers;
    }

    public Sso getSso() {
        return sso;
    }

    public Workbench getWorkbench() {
        return workbench;
    }

    public Avatar getAvatar() {
        return avatar;
    }

    public Client getClient() {
        return client;
    }

    public Ssrf getSsrf() {
        return ssrf;
    }

    public Callback getCallback() {
        return callback;
    }

    public Bot getBot() {
        return bot;
    }

    public Provision getProvision() {
        return provision;
    }

    public Livekit getLivekit() {
        return livekit;
    }
}
