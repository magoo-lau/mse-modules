package vip.mseapp.ai.autoconfigure;

import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import vip.mseapp.fs.api.condition.AbstractFsModeCondition;

/**
 * mse-fs 可用性条件（AI 智能体头像存储自动降级判据；判定五步与配置键语义见基类 {@link AbstractFsModeCondition}）。
 */
public class AvatarFsCondition extends AbstractFsModeCondition {

    public AvatarFsCondition() {
        super("mse.ai.avatar.storage-mode");
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isFsAvailable(context);
    }
}
