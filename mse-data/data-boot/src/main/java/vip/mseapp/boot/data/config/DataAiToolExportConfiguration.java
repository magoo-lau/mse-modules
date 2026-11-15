package vip.mseapp.boot.data.config;

import java.util.List;

import org.apache.dubbo.config.ServiceConfig;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import vip.mseapp.ai.api.RemoteAiTool;
import vip.mseapp.ai.api.support.DispatchingAiTool;
import vip.mseapp.data.service.DataQueryTools;
import vip.mseapp.rpc.dubbo.MseDubboSupport;

/**
 * 数据中台 AI 工具 Dubbo 导出（仅微服务 dubbo 模式）：group=spring.application.name（mse-data），
 * mse-ai 平台按 group 泛化调用（中台库受控查询通道）。
 *
 * <p>同 group 只能导出一个 RemoteAiTool 实现（mse-ai 按 interface+group 寻址，重复导出会被
 * 随机命中），多工具经 {@link DispatchingAiTool} 按 toolCode 内部分发——工具描述仍由容器内各
 * RemoteAiTool Bean 经 AiToolRegistrationPusher 逐个推送登记。当前承载：data_query
 * （中台库受控查询）；业务库查询已由 mse-ai 的 biz_query 路由门面转发各业务模块工具承接。
 *
 * <p>放在 data-boot 而非 data-service：Dubbo 依赖属启动器形态；单体聚合形态无需导出——
 * mse-ai 直接收集容器内 RemoteAiTool Bean。
 */
@Configuration
@ConditionalOnProperty(name = "mse.rpc.mode", havingValue = "dubbo")
public class DataAiToolExportConfiguration {

    @Bean(initMethod = "export", destroyMethod = "unexport")
    public ServiceConfig<RemoteAiTool> dataAiToolConfig(DataQueryTools dataQueryTools,
                                                        ConfigurableListableBeanFactory beanFactory,
                                                        Environment environment) {
        ServiceConfig<RemoteAiTool> config = MseDubboSupport.serviceConfig(RemoteAiTool.class,
                new DispatchingAiTool(List.of(dataQueryTools)), beanFactory);
        config.setGroup(environment.getProperty("spring.application.name"));
        return config;
    }
}
