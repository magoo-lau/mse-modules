package vip.mseapp.ai.api.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import vip.mseapp.ai.api.AiAgentProvider;
import vip.mseapp.ai.api.support.AiAgentRegistrationPusher;
import vip.mseapp.core.schedule.MseTaskScheduler;

/**
 * AI 智能体自注册推送装配：业务工程依赖 ai-api 即生效——容器内声明了 {@link AiAgentProvider}
 * Bean 的微服务（dubbo 模式）启动后，智能体描述自动推送至 mse-ai 智能体管理登记；
 * 单体聚合形态推送器自检闲置（mse-ai 本地收集已覆盖）。
 */
@AutoConfiguration
public class AiAgentRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AiAgentRegistrationPusher aiAgentRegistrationPusher(ObjectProvider<AiAgentProvider> providers,
                                                               Environment environment,
                                                               ConfigurableListableBeanFactory beanFactory,
                                                               ObjectProvider<MseTaskScheduler> schedulerProvider) {
        return new AiAgentRegistrationPusher(providers, environment, beanFactory, schedulerProvider);
    }
}
