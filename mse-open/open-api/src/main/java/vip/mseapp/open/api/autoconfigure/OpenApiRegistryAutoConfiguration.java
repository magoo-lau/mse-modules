package vip.mseapp.open.api.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import vip.mseapp.open.api.OpenApiContributor;
import vip.mseapp.open.api.support.OpenApiRegistrationPusher;

/**
 * 开放 API 自注册装配（随 open-api jar 经 SPI 生效）。
 *
 * <p>检测容器内的 {@link OpenApiContributor} Bean：存在则在应用就绪后推送其描述至开放平台
 * 目录并周期重推；不存在时推送器闲置（零副作用）。可用
 * {@code mse.open.registry.enabled=false} 整体关闭（如仅消费契约不参与自注册的部署）。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mse.open.registry", name = "enabled", matchIfMissing = true)
public class OpenApiRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenApiRegistrationPusher openApiRegistrationPusher(
            ObjectProvider<OpenApiContributor> contributors, Environment environment,
            ConfigurableListableBeanFactory beanFactory,
            ObjectProvider<vip.mseapp.core.schedule.MseTaskScheduler> schedulerProvider) {
        return new OpenApiRegistrationPusher(contributors, environment, beanFactory, schedulerProvider);
    }
}
