package vip.mseapp.fs.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import vip.mseapp.fs.config.FsStorageProperties;
import vip.mseapp.fs.storage.FileStorage;
import vip.mseapp.fs.storage.LocalFileStorage;
import vip.mseapp.fs.storage.RustFsFileStorage;

/**
 * 文件存储实现自动装配：按 {@code mse.fs.storage.type} 互斥装配 local（缺省）/ rustfs。
 *
 * <p>接入方可声明同类型 {@link FileStorage} Bean（如自研 OSS 实现）整体替换——
 * 三个分支均带 {@code @ConditionalOnMissingBean(FileStorage.class)} 让位。
 * type=rustfs 但 classpath 缺 SDK 时由 {@link RustFsMissingSdkConfiguration} 给出明确的启动报错。
 */
@AutoConfiguration
public class FsStorageAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "mse.fs.storage", name = "type", havingValue = "local", matchIfMissing = true)
    static class LocalStorageConfiguration {

        @Bean
        @ConditionalOnMissingBean(FileStorage.class)
        public FileStorage localFileStorage(FsStorageProperties properties) {
            return new LocalFileStorage(properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(S3Client.class)
    @ConditionalOnProperty(prefix = "mse.fs.storage", name = "type", havingValue = "rustfs")
    static class RustFsStorageConfiguration {

        @Bean
        @ConditionalOnMissingBean(FileStorage.class)
        public FileStorage rustFsFileStorage(FsStorageProperties properties) {
            return new RustFsFileStorage(properties);
        }
    }

    /** type=rustfs 但未引入 AWS SDK：启动即失败并说明原因（避免拖到首次上传才报 NoSuchBeanDefinition） */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "mse.fs.storage", name = "type", havingValue = "rustfs")
    @ConditionalOnMissingClass("software.amazon.awssdk.services.s3.S3Client")
    static class RustFsMissingSdkConfiguration {

        @Bean
        @ConditionalOnMissingBean(FileStorage.class)
        public FileStorage rustFsMissingSdkGuard() {
            throw new IllegalStateException(
                    "mse.fs.storage.type=rustfs 但 classpath 缺少 RustFS 客户端 SDK（software.amazon.awssdk:s3）："
                            + "请在启动器（fs-boot）引入该依赖，或将 mse.fs.storage.type 改为 local 使用本地文件存储");
        }
    }
}
