package vip.mseapp.im.autoconfigure;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;
import vip.mseapp.fs.api.RemoteFileService;

/**
 * mse-fs 存储能力条件基类（图标/头像等 IM 素材存储自动降级的共用判据，REGISTER_BEAN 阶段求值、
 * 以 afterName 保证在 FsServiceAutoConfiguration 之后评估）：
 * <ul>
 *   <li>开关属性值 = local → 恒 false（强制本地目录）；</li>
 *   <li>classpath 无 fs 契约（未引入 mse-fs）→ false，降级本地；</li>
 *   <li>auto（默认）且 {@code mse.rpc.mode=dubbo}（微服务形态）→ true，经 dubbo 调 mse-fs；</li>
 *   <li>auto 且 local 模式（单体）→ 需容器已装配 fs 契约实现（聚合了 fs-service）才 true。</li>
 * </ul>
 * 显式 =fs 时只要契约在 classpath 即 true（强制走 mse-fs，未部署在调用期报错）。
 */
public abstract class ImFsStorageCondition implements ConfigurationCondition {

    /** 开关属性键（如 mse.im.workbench.icon-storage / mse.im.avatar.storage） */
    private final String propertyKey;

    protected ImFsStorageCondition(String propertyKey) {
        this.propertyKey = propertyKey;
    }

    @Override
    public final ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String mode = env.getProperty(propertyKey, "auto");
        if ("local".equalsIgnoreCase(mode)) {
            return false;
        }
        if (!ClassUtils.isPresent("vip.mseapp.fs.api.RemoteFileService", getClass().getClassLoader())) {
            return false;
        }
        if ("fs".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("dubbo".equalsIgnoreCase(env.getProperty("mse.rpc.mode", "local"))) {
            return true;
        }
        // 单体 local 模式：仅当 fs-service 已在容器注册契约实现（避免 @RpcReference 本地解析启动失败）
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        return beanFactory != null
                && beanFactory.getBeanNamesForType(RemoteFileService.class, true, false).length > 0;
    }
}
