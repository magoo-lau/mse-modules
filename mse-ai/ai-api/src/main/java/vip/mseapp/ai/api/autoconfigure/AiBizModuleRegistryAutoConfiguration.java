package vip.mseapp.ai.api.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import vip.mseapp.ai.api.AiBizModuleProvider;
import vip.mseapp.ai.api.support.AiBizModuleRegistrationPusher;
import vip.mseapp.ai.api.support.AiBizQueryProperties;
import vip.mseapp.core.schedule.MseTaskScheduler;

/**
 * AI 业务模块自注册推送装配 + biz 查询工具配置：业务工程依赖 ai-api 即生效——容器内声明了
 * {@link AiBizModuleProvider} Bean 的微服务（dubbo 模式）启动后，模块描述自动推送至 mse-ai
 * 模块注册中心登记；单体聚合形态推送器自检闲置（mse-ai 本地收集已覆盖）。
 * {@link AiBizQueryProperties}（mse.ai.biz-query.*，模块查询工具的硬黑名单与限行配置）
 * 随本装配在所有宿主注册，模块工具基类注入消费。
 */
@AutoConfiguration
@EnableConfigurationProperties(AiBizQueryProperties.class)
public class AiBizModuleRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AiBizModuleRegistrationPusher aiBizModuleRegistrationPusher(
            ObjectProvider<AiBizModuleProvider> providers,
            Environment environment,
            ConfigurableListableBeanFactory beanFactory,
            ObjectProvider<MseTaskScheduler> schedulerProvider) {
        return new AiBizModuleRegistrationPusher(providers, environment, beanFactory, schedulerProvider);
    }
}
