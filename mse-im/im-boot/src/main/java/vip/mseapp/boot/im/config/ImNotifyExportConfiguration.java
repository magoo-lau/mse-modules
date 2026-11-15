package vip.mseapp.boot.im.config;

import org.apache.dubbo.config.ServiceConfig;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import vip.mseapp.im.notify.ImNotifyChannelFacade;
import vip.mseapp.api.notify.RemoteNotifyChannel;
import vip.mseapp.rpc.dubbo.MseDubboSupport;

/**
 * IM 通知渠道 Dubbo 导出（仅微服务 dubbo 模式）：group=spring.application.name（mse-im），
 * 框架通知中枢（基座 service-system）按登记目录的提供组定向调用。放在 im-boot 而非 im-service：
 * Dubbo 依赖属启动器形态（im-service 纯 jar 无 Dubbo classpath）；单体聚合形态无需导出——
 * 中枢直接收集容器内 ImNotifyChannelFacade Bean。渠道描述由 api-notify 推送器自动周期推送登记。
 */
@Configuration
@ConditionalOnProperty(name = "mse.rpc.mode", havingValue = "dubbo")
public class ImNotifyExportConfiguration {

    @Bean(initMethod = "export", destroyMethod = "unexport")
    public ServiceConfig<RemoteNotifyChannel> imNotifyChannelConfig(ImNotifyChannelFacade facade,
                                                                    ConfigurableListableBeanFactory beanFactory,
                                                                    Environment environment) {
        ServiceConfig<RemoteNotifyChannel> config = MseDubboSupport.serviceConfig(RemoteNotifyChannel.class,
                facade, beanFactory);
        config.setGroup(environment.getProperty("spring.application.name"));
        return config;
    }
}
