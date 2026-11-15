package vip.mseapp.ai.open;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import vip.mseapp.ai.entity.AiApiKey;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.log.LogContext;

import java.io.IOException;

/**
 * 开放 API 鉴权过滤器：/openapi/ai/** 走 appId + apiKey（网关已对前缀免会话校验）。
 *
 * <p>鉴权通过后把密钥实体挂到请求属性，控制器据此取业务租户与白名单；同时把
 * tenant_id 写入租户上下文（本通道无登录态，后续智能体可见性/模型圈定/会话/用量
 * 均按该租户行级隔离，请求结束由 TenantContextFilter 统一清理）；
 * 与基座登录态互不干扰（401/429 输出统一 JSON）。
 */
@Configuration
public class OpenApiFilterConfig {

    public static final String ATTR_KEY = "mse.openai.key";

    @Bean
    public FilterRegistrationBean<OpenApiAuthFilter> openApiAuthFilter(OpenApiKeyService keyService) {
        FilterRegistrationBean<OpenApiAuthFilter> registration =
                new FilterRegistrationBean<>(new OpenApiAuthFilter(keyService));
        registration.addUrlPatterns("/openapi/ai/*");
        registration.setOrder(10);
        return registration;
    }

    public static class OpenApiAuthFilter extends OncePerRequestFilter {

        private final OpenApiKeyService keyService;

        public OpenApiAuthFilter(OpenApiKeyService keyService) {
            this.keyService = keyService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String appId = request.getHeader("X-App-Id");
            String apiKey = request.getHeader("X-Api-Key");
            try {
                if (appId == null || apiKey == null) {
                    throw new ServiceException(401, "缺少 X-App-Id / X-Api-Key 请求头");
                }
                AiApiKey key = keyService.verify(appId, apiKey);
                if (!keyService.tryAcquire(key)) {
                    throw new ServiceException(429, "调用频次超限");
                }
                request.setAttribute(ATTR_KEY, key);
                TenantContext.setTenantId(key.getTenantId());
                // 本通道无登录态：租户上下文建立处同步日志上下文（MDC），业务日志可按租户检索
                LogContext.syncContext();
                chain.doFilter(request, response);
            } catch (ServiceException e) {
                response.setStatus(e.getCode() == 401 ? 401 : e.getCode() == 429 ? 429 : 400);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":" + e.getCode() + ",\"message\":"
                        + AiJson.toJson(e.getMessage()) + ",\"data\":null}");
            }
        }
    }
}
