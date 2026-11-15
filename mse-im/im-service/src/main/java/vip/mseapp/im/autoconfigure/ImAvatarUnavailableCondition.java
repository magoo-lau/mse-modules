package vip.mseapp.im.autoconfigure;

import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@link ImAvatarAvailableCondition} 的取反条件（头像本地目录兜底实现专用）。
 * 与 Fs 侧条件互斥（理由见 {@link WorkbenchIconUnavailableCondition}），注册顺序不影响结果。
 */
public class ImAvatarUnavailableCondition implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !new ImAvatarAvailableCondition().matches(context, metadata);
    }
}
