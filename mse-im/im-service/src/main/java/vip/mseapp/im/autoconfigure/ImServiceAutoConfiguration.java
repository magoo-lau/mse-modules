package vip.mseapp.im.autoconfigure;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import vip.mseapp.core.annotation.TenantServiceModule;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.service.FsImAvatarStorage;
import vip.mseapp.im.service.FsWorkbenchIconStorage;
import vip.mseapp.im.service.ImAvatarStorage;
import vip.mseapp.im.service.LocalImAvatarStorage;
import vip.mseapp.im.service.LocalWorkbenchIconStorage;
import vip.mseapp.im.service.OpenPlatformGateway;
import vip.mseapp.im.service.WorkbenchIconStorage;
import vip.mseapp.mybatis.config.MseMybatisAutoConfiguration;

/**
 * IM 业务服务自动装配（Spring Boot SPI）。
 *
 * <p>接入方仅需引入 im-service 依赖：配置 / OpenIM 客户端 / 实例选址 / 会话签发 / SSO /
 * 工作台入口与外部应用免登授权 / 实例台账与机器人（P1）/ webhook 消费与开通编排（P2）
 * 均由此注册。master 通道业务表（im_instance_config / im_bot / im_workbench_item 等）
 * 随 jar 版本化脚本自动建表。控制器由 {@link ImControllerAutoConfiguration} 装配，
 * 两者均经 {@code META-INF/spring/...AutoConfiguration.imports} 登记。
 * 服务身份 {@code @TenantServiceModule("mse-im")}：单体聚合接入时「租户隔离策略」
 * 服务级配置零 yaml 生效。
 *
 * <p>装配顺序：afterName 保证图标存储条件在 FsServiceAutoConfiguration、开放平台网关条件在
 * OpenServiceAutoConfiguration 之后评估；Fs/Local 两侧条件互斥（不可依赖嵌套类声明顺序，
 * javac 编译 InnerClasses 不保序）。
 */
@AutoConfiguration(before = MseMybatisAutoConfiguration.class,
        afterName = {"vip.mseapp.fs.autoconfigure.FsServiceAutoConfiguration",
                "vip.mseapp.open.autoconfigure.OpenServiceAutoConfiguration"})
@MapperScan(basePackages = "vip.mseapp.im.mapper", annotationClass = Mapper.class)
@ComponentScan(basePackages = {"vip.mseapp.im.config", "vip.mseapp.im.client", "vip.mseapp.im.service",
        "vip.mseapp.im.support", "vip.mseapp.im.entity", "vip.mseapp.im.notify"})
@TenantServiceModule("mse-im")
public class ImServiceAutoConfiguration {

    // ===== 工作台图标存储（mse-fs 优先、本地磁盘兜底）=====
    // 两侧条件互斥（理由见 WorkbenchIconUnavailableCondition）

    @Configuration(proxyBeanMethods = false)
    static class FsWorkbenchIconStorageConfiguration {

        @Bean
        @Conditional(WorkbenchIconAvailableCondition.class)
        @ConditionalOnMissingBean(WorkbenchIconStorage.class)
        public WorkbenchIconStorage fsWorkbenchIconStorage() {
            return new FsWorkbenchIconStorage();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LocalWorkbenchIconStorageConfiguration {

        @Bean
        @Conditional(WorkbenchIconUnavailableCondition.class)
        @ConditionalOnMissingBean(WorkbenchIconStorage.class)
        public WorkbenchIconStorage workbenchIconStorage(MseImProperties properties) {
            return new LocalWorkbenchIconStorage(properties.getWorkbench().getIconDir());
        }
    }

    // ===== 聊天客户端用户头像存储（mse-fs 优先、本地磁盘兜底）=====
    // 两侧条件互斥（理由见 WorkbenchIconUnavailableCondition）

    @Configuration(proxyBeanMethods = false)
    static class FsImAvatarStorageConfiguration {

        @Bean
        @Conditional(ImAvatarAvailableCondition.class)
        @ConditionalOnMissingBean(ImAvatarStorage.class)
        public ImAvatarStorage fsImAvatarStorage() {
            return new FsImAvatarStorage();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LocalImAvatarStorageConfiguration {

        @Bean
        @Conditional(ImAvatarUnavailableCondition.class)
        @ConditionalOnMissingBean(ImAvatarStorage.class)
        public ImAvatarStorage imAvatarStorage(MseImProperties properties) {
            return new LocalImAvatarStorage(properties.getAvatar().getDir());
        }
    }

    // ===== 开放平台应用契约网关（免登授权凭证归口 mse-open；未聚合 open-service 时缺省不装配）=====

    @Configuration(proxyBeanMethods = false)
    static class OpenPlatformGatewayConfiguration {

        @Bean
        @Conditional(OpenAppAvailableCondition.class)
        @ConditionalOnMissingBean(OpenPlatformGateway.class)
        public OpenPlatformGateway openPlatformGateway() {
            return new OpenPlatformGateway();
        }
    }
}
