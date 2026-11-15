package vip.mseapp.data.autoconfigure;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import vip.mseapp.mybatis.config.MseMybatisAutoConfiguration;
import vip.mseapp.data.api.RemoteDataApiService;
import vip.mseapp.data.mapper.DatApiMapper;
import vip.mseapp.data.openapi.DataOpenApiContributor;
import vip.mseapp.data.open.ApiQueryExecutor;
import vip.mseapp.data.rpc.RemoteDataApiServiceImpl;
import vip.mseapp.core.annotation.TenantServiceModule;

/**
 * 数据中台业务服务自动装配（Spring Boot SPI）。
 *
 * <p>接入方仅需引入 data-service 依赖：Service / 同步引擎与调度 / 连接器管理 /
 * 开放 API 组件 / 配置与加解密 / Mapper 均由此注册，无需 component-scan 业务包名，
 * 也无需 @MapperScan。控制器由 {@link DataControllerAutoConfiguration} 装配，两者均经
 * {@code META-INF/spring/...AutoConfiguration.imports} 登记。
 *
 * <p>{@code config} 包内 DataEngineConfig 携带 @EnableScheduling（内存态调度器，
 * 多实例部署需外置调度），经本类扫描生效。
 */
@AutoConfiguration(before = MseMybatisAutoConfiguration.class)
@MapperScan(basePackages = "vip.mseapp.data.mapper", annotationClass = Mapper.class)
@ComponentScan(basePackages = {"vip.mseapp.data.ai", "vip.mseapp.data.config", "vip.mseapp.data.engine",
        "vip.mseapp.data.open", "vip.mseapp.data.service", "vip.mseapp.data.support"})
@TenantServiceModule("mse-data")
public class DataServiceAutoConfiguration {

    /** 开放平台数据 API 契约实现（受控执行通道）：可声明同契约 Bean 整体替换 */
    @Bean
    @ConditionalOnMissingBean(RemoteDataApiService.class)
    public RemoteDataApiService remoteDataApiService(DatApiMapper apiMapper, ApiQueryExecutor executor,
                                                     tools.jackson.databind.json.JsonMapper objectMapper) {
        return new RemoteDataApiServiceImpl(apiMapper, executor, objectMapper);
    }

    /** 开放能力声明（开放平台自注册；按 Bean 名判重，避免多积木同类型贡献者互相让位） */
    @Bean(name = "dataOpenApiContributor")
    @ConditionalOnMissingBean(name = "dataOpenApiContributor")
    public vip.mseapp.open.api.OpenApiContributor dataOpenApiContributor() {
        return new DataOpenApiContributor();
    }
}
