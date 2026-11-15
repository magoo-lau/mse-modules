package vip.mseapp.open.autoconfigure;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;
import vip.mseapp.mybatis.config.MseMybatisAutoConfiguration;
import vip.mseapp.open.api.RemoteOpenApiRegistry;
import vip.mseapp.open.api.RemoteOpenAppService;
import vip.mseapp.open.config.OpenProperties;
import vip.mseapp.open.dispatch.OpenRpcDispatcher;
import vip.mseapp.open.rpc.RemoteOpenApiRegistryImpl;
import vip.mseapp.open.rpc.RemoteOpenAppServiceImpl;
import vip.mseapp.open.security.OpenTokenService;
import vip.mseapp.open.service.OpenApiRegistryService;
import vip.mseapp.open.service.OpenAppService;
import vip.mseapp.core.annotation.TenantServiceModule;

/**
 * 业务开放平台业务服务自动装配（Spring Boot SPI）。
 *
 * <p>接入方仅需引入 open-service 依赖：Service / 支撑组件（加解密、配置、限流、令牌）
 * / 目录注册接收端（RemoteOpenApiRegistry）/ 泛化分发器 / 租户白名单注册器 / Web 安全配置
 * / Mapper 均由此注册，无需 component-scan 业务包名，也无需 @MapperScan。
 * 控制器由 {@link OpenControllerAutoConfiguration} 装配，两者均经
 * {@code META-INF/spring/...AutoConfiguration.imports} 登记。
 */
@AutoConfiguration(before = MseMybatisAutoConfiguration.class)
@MapperScan(basePackages = "vip.mseapp.open.mapper", annotationClass = Mapper.class)
@ComponentScan(basePackages = {"vip.mseapp.open.config", "vip.mseapp.open.service",
        "vip.mseapp.open.security", "vip.mseapp.open.dispatch", "vip.mseapp.open.tenant"})
@TenantServiceModule("mse-open")
public class OpenServiceAutoConfiguration {

    /** 目录注册契约实现：接入方声明自己的同契约 Bean 即可整体替换 */
    @Bean
    @ConditionalOnMissingBean(RemoteOpenApiRegistry.class)
    public RemoteOpenApiRegistry remoteOpenApiRegistry(OpenApiRegistryService registryService) {
        return new RemoteOpenApiRegistryImpl(registryService);
    }

    /** 接入应用契约实现（只读 + 校验，凭证变更归口管理端）：同契约 Bean 可整体替换 */
    @Bean
    @ConditionalOnMissingBean(RemoteOpenAppService.class)
    public RemoteOpenAppService remoteOpenAppService(OpenAppService appService, OpenTokenService tokenService) {
        return new RemoteOpenAppServiceImpl(appService, tokenService);
    }

    /** 泛化分发器（非组件扫描注册：需 BeanFactory/Environment 编程式解析） */
    @Bean
    @ConditionalOnMissingBean
    public OpenRpcDispatcher openRpcDispatcher(ConfigurableListableBeanFactory beanFactory,
                                               Environment environment, OpenProperties properties) {
        return new OpenRpcDispatcher(beanFactory, environment, properties);
    }
}
