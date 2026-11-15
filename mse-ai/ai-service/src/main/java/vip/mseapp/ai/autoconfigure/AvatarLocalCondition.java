package vip.mseapp.ai.autoconfigure;

import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import vip.mseapp.fs.api.condition.AbstractFsModeCondition;

/**
 * {@link AvatarFsCondition} 的取反条件（头像本地兜底实现专用）。两侧显式互斥、不依赖注册顺序
 * （javac InnerClasses 不保序，勿改回 @ConditionalOnMissingBean 让位式——Fs 侧先让位即静默落本地）。
 */
public class AvatarLocalCondition extends AbstractFsModeCondition {

    public AvatarLocalCondition() {
        super("mse.ai.avatar.storage-mode");
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !isFsAvailable(context);
    }
}
