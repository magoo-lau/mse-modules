package vip.mseapp.fs.autoconfigure;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.fs.api.RemoteFileService;
import vip.mseapp.fs.openapi.FsOpenApiContributor;
import vip.mseapp.fs.rpc.RemoteFileServiceImpl;
import vip.mseapp.fs.service.FsFileService;
import vip.mseapp.mybatis.config.MseMybatisAutoConfiguration;
import vip.mseapp.open.api.OpenApiContributor;
import vip.mseapp.core.annotation.TenantServiceModule;

/**
 * 文件存储业务服务自动装配（Spring Boot SPI）。
 *
 * <p>接入方仅需引入 fs-service 依赖：配置（FsStorageProperties）/ Service / 存储 RPC 实现
 * / Mapper 均由此注册，无需 component-scan 业务包名，也无需 @MapperScan。
 * 存储实现（local/rustfs）由 {@link FsStorageAutoConfiguration} 按 mse.fs.storage.type 装配；
 * 控制器由 {@link FsControllerAutoConfiguration} 装配，两者均经
 * {@code META-INF/spring/...AutoConfiguration.imports} 登记。
 */
@AutoConfiguration(before = MseMybatisAutoConfiguration.class)
@MapperScan(basePackages = "vip.mseapp.fs.mapper", annotationClass = Mapper.class)
@ComponentScan(basePackages = {"vip.mseapp.fs.config", "vip.mseapp.fs.service", "vip.mseapp.fs.rpc"})
@TenantServiceModule("mse-fs")
public class FsServiceAutoConfiguration {

    /** 文件存储 RPC 契约实现：可声明同契约 Bean 整体替换 */
    @Bean
    @ConditionalOnMissingBean(RemoteFileService.class)
    public RemoteFileService remoteFileService(FsFileService fileService, JsonMapper objectMapper) {
        return new RemoteFileServiceImpl(fileService, objectMapper);
    }

    /** 开放能力声明（开放平台自注册；按 Bean 名判重，避免多积木同类型贡献者互相让位） */
    @Bean(name = "fsOpenApiContributor")
    @ConditionalOnMissingBean(name = "fsOpenApiContributor")
    public OpenApiContributor fsOpenApiContributor() {
        return new FsOpenApiContributor();
    }
}
