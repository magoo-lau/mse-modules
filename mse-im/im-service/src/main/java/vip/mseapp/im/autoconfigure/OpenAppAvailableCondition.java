package vip.mseapp.im.autoconfigure;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;
import vip.mseapp.open.api.RemoteOpenAppService;

/**
 * 开放平台（mse-open）可用性条件（工作台免登授权契约网关的装配判据，REGISTER_BEAN 阶段求值）：
 * <ul>
 *   <li>classpath 无 open 契约（未引入 mse-open）→ false，免登授权不可用（直连入口不受影响）；</li>
 *   <li>{@code mse.rpc.mode=dubbo}（微服务形态）→ true，经 dubbo 调 mse-open（未部署在调用期报错）；</li>
 *   <li>local 模式（单体）→ 需容器已装配 open 契约实现（聚合了 open-service）才 true，
 *       避免 {@code @RpcReference} 本地解析启动失败。</li>
 * </ul>
 */
public class OpenAppAvailableCondition implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (!ClassUtils.isPresent("vip.mseapp.open.api.RemoteOpenAppService", getClass().getClassLoader())) {
            return false;
        }
        Environment env = context.getEnvironment();
        if ("dubbo".equalsIgnoreCase(env.getProperty("mse.rpc.mode", "local"))) {
            return true;
        }
        // 单体 local 模式：仅当 open-service 已在容器注册契约实现
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        return beanFactory != null
                && beanFactory.getBeanNamesForType(RemoteOpenAppService.class, true, false).length > 0;
    }
}
