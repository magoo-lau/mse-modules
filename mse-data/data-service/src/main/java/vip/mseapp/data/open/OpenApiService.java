package vip.mseapp.data.open;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.data.config.DataCrypt;
import vip.mseapp.data.config.DataProperties;
import vip.mseapp.data.entity.DatApi;
import vip.mseapp.data.entity.DatApiApp;
import vip.mseapp.data.entity.DatApiGrant;
import vip.mseapp.data.entity.DatApiLog;
import vip.mseapp.data.mapper.DatApiAppMapper;
import vip.mseapp.data.mapper.DatApiGrantMapper;
import vip.mseapp.data.mapper.DatApiLogMapper;
import vip.mseapp.data.mapper.DatApiMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 开放调用管线：appKey 定位租户 → 授权校验 → 限流 → 执行 → 调用审计。
 *
 * <p>凭证世界化（标识符自带世界段）：appKey 格式 {@code dat-{tenantId}-{hex}}——
 * 开放调用无登录态，解析 appKey 世界段后 {@link TenantContext#runAs} 切到应用所属世界
 * （一切皆租户：独立库租户专属库 / 共享租户绑定库 / 平台世界 0 平台库）读取校验；
 * 旧格式 appKey（dat-{hex}，无世界段）主库 runIgnore 兜底（过渡期 deprecated，
 * 独立库租户旧凭证请重置密钥换发）。后续授权校验/执行/审计均在租户语义内落库，可完整审计每次调用。
 */
@Service
public class OpenApiService {

    private static final DateTimeFormatter MINUTE_BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final DatApiAppMapper appMapper;
    private final DatApiMapper apiMapper;
    private final DatApiGrantMapper grantMapper;
    private final DatApiLogMapper logMapper;
    private final DataCrypt crypt;
    private final OpenSignatureVerifier signatureVerifier;
    private final ApiQueryExecutor executor;
    private final RedisTemplate<String, Object> redisTemplate;
    private final DataProperties properties;

    public OpenApiService(DatApiAppMapper appMapper, DatApiMapper apiMapper,
                          DatApiGrantMapper grantMapper, DatApiLogMapper logMapper,
                          DataCrypt crypt, OpenSignatureVerifier signatureVerifier,
                          ApiQueryExecutor executor, RedisTemplate<String, Object> redisTemplate,
                          DataProperties properties) {
        this.appMapper = appMapper;
        this.apiMapper = apiMapper;
        this.grantMapper = grantMapper;
        this.logMapper = logMapper;
        this.crypt = crypt;
        this.signatureVerifier = signatureVerifier;
        this.executor = executor;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /** 开放调用入口（HTTP 层仅做透传） */
    public OpenResponse invoke(String appKey, String path, OpenRequest request, String clientIp) {
        DatApiApp app = findApp(appKey);
        if (app == null || app.getStatus() != DatApiApp.STATUS_ENABLED) {
            throw new ServiceException(401, "应用不存在或已停用");
        }
        String secret = crypt.decrypt(app.getAppSecret());
        signatureVerifier.verify(appKey, secret, request.timestamp(), request.nonce(),
                request.sign());
        return TenantContext.runAs(app.getTenantId(), () -> doInvoke(app, path, request, clientIp));
    }

    /** 按 appKey 定位应用行：世界段解析切库（一切皆租户）；旧格式（无世界段）主库兜底 */
    private DatApiApp findApp(String appKey) {
        Long world = parseWorld(appKey);
        if (world != null) {
            return TenantContext.runAs(world, () -> appMapper.selectOne(
                    new LambdaQueryWrapper<DatApiApp>().eq(DatApiApp::getAppKey, appKey)));
        }
        return TenantContext.runIgnore(() -> appMapper.selectOne(
                new LambdaQueryWrapper<DatApiApp>().eq(DatApiApp::getAppKey, appKey)));
    }

    /** 解析 appKey 世界段（dat-{tenantId}-{hex}）；旧格式（dat-{hex}，无世界段）返回 null */
    static Long parseWorld(String appKey) {
        if (appKey == null || !appKey.startsWith("dat-")) {
            return null;
        }
        String rest = appKey.substring(4);
        int split = rest.indexOf('-');
        if (split <= 0) {
            return null;
        }
        try {
            return Long.valueOf(rest.substring(0, split));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private OpenResponse doInvoke(DatApiApp app, String path, OpenRequest request, String clientIp) {
        DatApi api = apiMapper.selectOne(new LambdaQueryWrapper<DatApi>()
                .eq(DatApi::getPath, path));
        if (api == null) {
            throw new ServiceException(404, "API 不存在：" + path);
        }
        if (api.getStatus() != DatApi.STATUS_PUBLISHED) {
            throw new ServiceException(400, "API 已下线");
        }
        checkGrant(api.getId(), app.getId());
        checkRateLimit(app.getAppKey(), api);
        return executeAndLog(api, app.getId(), request.params(), false,
                request.pageNum(), request.pageSize(), clientIp);
    }

    /** 管理端试运行（跳过授权/限流/签名，走当前登录租户上下文，仅取 10 行） */
    public OpenResponse preview(DatApi api, Map<String, Object> params) {
        return executeAndLog(api, null, params, true, null, null, null);
    }

    private void checkGrant(Long apiId, Long appId) {
        DatApiGrant grant = grantMapper.selectOne(new LambdaQueryWrapper<DatApiGrant>()
                .eq(DatApiGrant::getApiId, apiId)
                .eq(DatApiGrant::getAppId, appId));
        if (grant == null) {
            throw new ServiceException(403, "应用未被授权调用该 API");
        }
        if (grant.getExpireTime() != null && grant.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException(403, "授权已过期");
        }
    }

    private void checkRateLimit(String appKey, DatApi api) {
        int limit = api.getQpsLimit() != null && api.getQpsLimit() > 0
                ? api.getQpsLimit() : properties.getOpen().getDefaultPerMinute();
        String key = "mse:data:open:rl:" + appKey + ":"
                + LocalDateTime.now().format(MINUTE_BUCKET);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(90));
        }
        if (count != null && count > limit) {
            throw new ServiceException(429, "调用频次超限（每分钟 " + limit + " 次）");
        }
    }

    private OpenResponse executeAndLog(DatApi api, Long appId, Map<String, Object> params,
                                       boolean preview, Integer pageNum, Integer pageSize,
                                       String clientIp) {
        DatApiLog log = new DatApiLog();
        log.setApiId(api.getId());
        log.setAppId(appId == null ? 0L : appId);
        log.setCostMs(0L);
        log.setStatus(DatApiLog.STATUS_SUCCESS);
        try {
            log.setParams(truncate(toJson(params), 2000));
        } catch (Exception ignored) {
            log.setParams(null);
        }
        log.setClientIp(clientIp);
        try {
            ApiQueryExecutor.QueryResult result =
                    executor.execute(api, params, true, pageNum, pageSize, preview);
            log.setRowCount(result.rows().size());
            log.setCostMs(result.costMs());
            logMapper.insert(log);
            return new OpenResponse(0, "ok", new OpenResponse.Payload(
                    result.columns(), result.rows(), result.hasMore(), result.costMs()));
        } catch (ServiceException e) {
            log.setStatus(DatApiLog.STATUS_FAILED);
            log.setErrorMsg(truncate(e.getMessage(), 1000));
            logMapper.insert(log);
            throw e;
        } catch (Exception e) {
            log.setStatus(DatApiLog.STATUS_FAILED);
            log.setErrorMsg(truncate(String.valueOf(e.getMessage()), 1000));
            logMapper.insert(log);
            throw new ServiceException(500, "调用失败：" + e.getMessage());
        }
    }

    private String toJson(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String message, int max) {
        if (message == null) {
            return null;
        }
        return message.length() > max ? message.substring(0, max) : message;
    }

    /** 开放请求（签名头 + 调用参数） */
    public record OpenRequest(String timestamp, String nonce, String sign,
                              Map<String, Object> params, Integer pageNum, Integer pageSize) {
    }

    /** 开放响应（对调用方的稳定契约，不走基座 Result 包装） */
    public record OpenResponse(int code, String message, Payload data) {

        public record Payload(java.util.List<String> columns,
                              java.util.List<Map<String, Object>> rows,
                              boolean hasMore, Long costMs) {
        }
    }
}
