package vip.mseapp.open.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vip.mseapp.open.config.OpenProperties;
import vip.mseapp.open.mapper.OpenAppApiMapper;
import vip.mseapp.open.mapper.OpenAppMapper;
import vip.mseapp.open.service.OpenApiLogService;

import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.Executor;

/**
 * 开放平台 Web 装配：注册 /open/api/** 请求体缓存过滤器 + 鉴权拦截器 + 审计异步线程池。
 *
 * <p>拦截器只映射开放 API 路径，不影响基座 Sa-Token 对 /auth/**、/system/**、/openapp/** 的鉴权；
 * open-service 的组件（service/controller/config）由启动器默认扫描接管，本类只负责
 * 需要 InterceptorRegistry / FilterRegistrationBean 的装配。
 */
@Configuration
@EnableAsync(proxyTargetClass = true)
public class OpenWebConfig implements WebMvcConfigurer {

    private final OpenAppMapper appMapper;
    private final OpenAppApiMapper appApiMapper;
    private final OpenApiCatalogService catalog;
    private final OpenTokenService tokenService;
    private final OpenRateLimiter rateLimiter;
    private final OpenSignatureService signatureService;
    private final OpenApiLogService logService;
    private final JsonMapper objectMapper;

    public OpenWebConfig(OpenAppMapper appMapper, OpenAppApiMapper appApiMapper,
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
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OpenApiAuthInterceptor(appMapper, appApiMapper, catalog,
                        tokenService, rateLimiter, signatureService, logService, objectMapper))
                .addPathPatterns("/open/api/**");
    }

    /** 请求体缓存过滤器（先于拦截器执行）：签名验签与 @RequestBody 共享同一份可重复读体 */
    @Bean
    public FilterRegistrationBean<OpenBodyCachingFilter> openBodyCachingFilter() {
        FilterRegistrationBean<OpenBodyCachingFilter> registration =
                new FilterRegistrationBean<>(new OpenBodyCachingFilter());
        registration.addUrlPatterns("/open/api/*");
        registration.setOrder(1);
        return registration;
    }

    /** 审计日志专用线程池：CallerRuns 背压保证不丢日志，极端流量下退化为同步落库；
     *  TaskDecorator 捕获提交线程租户上下文、执行时恢复（开放调用审计随世界路由落库） */
    @Bean
    public Executor openApiLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("open-audit-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(2000);
        executor.setTaskDecorator(task ->
                io.micrometer.context.ContextSnapshot.captureAll().wrap(task));
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
