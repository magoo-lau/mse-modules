package vip.mseapp.search.api.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import vip.mseapp.search.api.SearchSourceContributor;
import vip.mseapp.search.api.support.SearchSourceRegistrationPusher;

/**
 * 可搜索源自注册装配（随 search-api jar 经 SPI 生效）。
 *
 * <p>检测容器内的 {@link SearchSourceContributor} Bean：存在则在应用就绪后推送其源描述至
 * 搜索引擎目录并周期重推；不存在时推送器闲置（零副作用）。可用
 * {@code mse.search.registry.enabled=false} 整体关闭（如仅消费契约不参与自注册的部署）。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mse.search.registry", name = "enabled", matchIfMissing = true)
public class SearchSourceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SearchSourceRegistrationPusher searchSourceRegistrationPusher(
            ObjectProvider<SearchSourceContributor> contributors, Environment environment,
            ConfigurableListableBeanFactory beanFactory,
            ObjectProvider<vip.mseapp.core.schedule.MseTaskScheduler> schedulerProvider) {
        return new SearchSourceRegistrationPusher(contributors, environment, beanFactory, schedulerProvider);
    }
}
