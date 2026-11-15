package vip.mseapp.ai.api.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import vip.mseapp.ai.api.RemoteAiTool;
import vip.mseapp.ai.api.support.AiToolRegistrationPusher;
import vip.mseapp.core.schedule.MseTaskScheduler;

/**
 * AI 工具自注册推送装配：业务工程依赖 ai-api 即生效——容器内声明了 {@link RemoteAiTool}
 * Bean 的微服务（dubbo 模式）启动后，工具描述自动推送至 mse-ai 工具中心登记；
 * 单体聚合形态推送器自检闲置（mse-ai 本地收集已覆盖）。
 */
@AutoConfiguration
public class AiToolRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AiToolRegistrationPusher aiToolRegistrationPusher(ObjectProvider<RemoteAiTool> tools,
                                                             Environment environment,
                                                             ConfigurableListableBeanFactory beanFactory,
                                                             ObjectProvider<MseTaskScheduler> schedulerProvider) {
        return new AiToolRegistrationPusher(tools, environment, beanFactory, schedulerProvider);
    }
}
