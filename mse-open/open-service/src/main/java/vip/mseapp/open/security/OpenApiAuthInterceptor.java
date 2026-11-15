package vip.mseapp.open.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.log.LogContext;
import vip.mseapp.core.result.Result;
import vip.mseapp.open.entity.OpenApiDef;
import vip.mseapp.open.entity.OpenApiLog;
import vip.mseapp.open.entity.OpenApp;
import vip.mseapp.open.entity.OpenAppApi;
import vip.mseapp.open.mapper.OpenAppApiMapper;
import vip.mseapp.open.mapper.OpenAppMapper;
import vip.mseapp.open.service.OpenApiLogService;

import java.time.LocalDateTime;

/**
 * 开放 API 鉴权拦截器（/open/api/**）：机器凭证链路，与 Sa-Token 人机会话完全独立。
 *
 * <p>校验顺序：Bearer token → 应用实时状态（停用/过期/删除即时失效）→ IP 白名单 →
 * 域名白名单（Origin/Referer）→ 请求签名验签（sign_required 应用强制，覆盖方法/路径/query/时间戳/nonce/body 摘要）→
 * API 目录匹配 → 应用授权（open_app_api）→ 应用限流。全部通过后注入租户上下文
 * （数据隔离锚点，请求出口由基座清理拦截器统一回收），并登记审计信息供 afterCompletion 异步落库。
 */
public class OpenApiAuthInterceptor implements HandlerInterceptor {

    /** request attribute：审计起点（毫秒时间戳） */
    private static final String ATTR_START = "open.audit.start";
    private static final String ATTR_APP = "open.audit.app";
    private static final String ATTR_API_CODE = "open.audit.apiCode";
    private static final String ATTR_ERROR = "open.audit.error";

    /** request attribute：命中的目录条目（泛化分发控制器消费，见 dispatch 包） */
    public static final String ATTR_API_DEF = "open.api.def";

    /** request attribute：审计已落（防 reject 补记与 afterCompletion 重复） */
    private static final String ATTR_RECORDED = "open.audit.recorded";

    private final OpenAppMapper appMapper;
    private final OpenAppApiMapper appApiMapper;
    private final OpenApiCatalogService catalog;
    private final OpenTokenService tokenService;
    private final OpenRateLimiter rateLimiter;
    private final OpenSignatureService signatureService;
    private final OpenApiLogService logService;
    private final JsonMapper objectMapper;

    public OpenApiAuthInterceptor(OpenAppMapper appMapper, OpenAppApiMapper appApiMapper,
                                  OpenApiCatalogService catalog, OpenTokenService tokenService,
                                  OpenRateLimiter rateLimiter, OpenSignatureService signatureService,
                                  OpenApiLogService logService, JsonMapper objectMapper) {
        this.appMapper = appMapper;
        this.appApiMapper = appApiMapper;
        this.catalog = catalog;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.signatureService = signatureService;
        this.logService = logService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        request.setAttribute(ATTR_START, System.currentTimeMillis());

        String token = resolveBearerToken(request);
        if (token == null) {
            return reject(request, response, 401, "缺少访问凭证（Authorization: Bearer {token}）");
        }
        OpenAppSession session = tokenService.resolve(token).orElse(null);
        if (session == null) {
            return reject(request, response, 401, "访问凭证无效或已过期");
        }
        // 应用行随绑定租户世界驻留：按 token 会话的租户快照路由读取实时状态
        //（跨世界改绑不支持，快照失配即读不到行 → 401 重新换发；会话快照 TTL 兜底）
        OpenApp app = TenantContext.runAs(session.tenantId(), () -> appMapper.selectById(session.appId()));
        if (app == null || (app.getStatus() != null && app.getStatus() != OpenApp.STATUS_ENABLED)) {
            tokenService.revoke(token);
            return reject(request, response, 401, "应用已停用或已删除，访问凭证失效");
        }
        if (app.getExpireTime() != null && app.getExpireTime().isBefore(LocalDateTime.now())) {
            return reject(request, response, 401, "应用凭证已过期");
        }
        // 二期安全：IP 白名单 → 域名白名单（浏览器侧来源约束，Origin/Referer；租户上下文建立前 fail-fast，失败同样记审计）
        if (!OpenIpWhitelist.allowed(app.getIpWhitelist(), resolveClientIp(request))) {
            return reject(request, response, 403, "客户端 IP 不在应用白名单内");
        }
        if (!OpenDomainWhitelist.allowed(app.getDomainWhitelist(), OpenDomainWhitelist.originHost(request))) {
            return reject(request, response, 403, "请求来源域名不在应用白名单内");
        }
        if (app.getSignRequired() != null && app.getSignRequired() == 1) {
            byte[] body = (byte[]) request.getAttribute(OpenBodyCachingFilter.ATTR_CACHED_BODY);
            String signError = signatureService.verify(app, request, body);
            if (signError != null) {
                return reject(request, response, 401, signError);
            }
        }
        // 数据隔离锚点提前：后续授权校验（open_app_api）与业务分发全部约束在应用绑定租户域内
        TenantContext.setTenantId(app.getTenantId());
        // 开放 API 无 Sa-Token 登录态：此处为日志上下文（MDC）的租户镜像点，业务与审计日志可按租户检索
        LogContext.syncContext();
        request.setAttribute(ATTR_APP, app);

        OpenApiDef api = catalog.match(request.getMethod(), request.getRequestURI());
        if (api == null) {
            return reject(request, response, 404,
                    "API 未开放: " + request.getMethod() + " " + request.getRequestURI());
        }
        request.setAttribute(ATTR_API_CODE, api.getCode());
        request.setAttribute(ATTR_API_DEF, api);

        // 授权行随应用绑定租户世界驻留（OpenAppService grantApis 同世界写入）；
        // 此处租户上下文已注入（setTenantId），随世界路由 + appId/apiId 等值定位
        Long granted = appApiMapper.selectCount(
                new LambdaQueryWrapper<OpenAppApi>()
                        .eq(OpenAppApi::getAppId, app.getId())
                        .eq(OpenAppApi::getApiId, api.getId()));
        if (granted == null || granted == 0) {
            return reject(request, response, 403, "应用未授权调用该 API: " + api.getCode());
        }
        if (!rateLimiter.tryAcquire(app)) {
            return reject(request, response, 429, "调用频率超限");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        writeAudit(request, response);
    }

    /**
     * 审计落库（异步）。拒绝类（401/403/404/429）必须在此补记：Spring 的 HandlerExecutionChain
     * 只对 preHandle 返回 true 的拦截器回调 afterCompletion（interceptorIndex 不含失败者自身），
     * reject 返回 false 后本拦截器的 afterCompletion 永不执行——曾致四类拒绝的审计全部丢失。
     */
    private void writeAudit(HttpServletRequest request, HttpServletResponse response) {
        Long start = (Long) request.getAttribute(ATTR_START);
        if (start == null || request.getAttribute(ATTR_RECORDED) != null) {
            return; // 未进入 preHandle 的请求不记审计；已记录过的不重复
        }
        request.setAttribute(ATTR_RECORDED, Boolean.TRUE);
        OpenApp app = (OpenApp) request.getAttribute(ATTR_APP);
        OpenApiLog entry = new OpenApiLog();
        entry.setTenantId(app != null ? app.getTenantId() : TenantContext.getTenantId());
        entry.setAppId(app == null ? null : app.getId());
        entry.setApiCode((String) request.getAttribute(ATTR_API_CODE));
        entry.setMethod(request.getMethod());
        entry.setPath(request.getRequestURI());
        entry.setIp(resolveClientIp(request));
        entry.setStatusCode(response.getStatus());
        entry.setCostMs(System.currentTimeMillis() - start);
        entry.setErrorMsg((String) request.getAttribute(ATTR_ERROR));
        entry.setTraceId(MDC.get(LogContext.TRACE_ID));
        logService.recordAsync(entry);
    }

    /** 401/403/404/429 统一应答与审计标记 */
    private boolean reject(HttpServletRequest request, HttpServletResponse response,
                           int status, String message) throws Exception {
        request.setAttribute(ATTR_ERROR, message);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(
                objectMapper.writeValueAsBytes(Result.fail(status, message)));
        response.getOutputStream().flush();
        writeAudit(request, response);
        return false;
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String header = request.getHeader(MseConstants.TOKEN_NAME);
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return null;
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
