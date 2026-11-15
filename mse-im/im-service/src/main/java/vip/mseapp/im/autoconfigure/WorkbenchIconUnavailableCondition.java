package vip.mseapp.im.autoconfigure;

import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@link WorkbenchIconAvailableCondition} 的取反条件（工作台图标本地目录兜底实现专用）。
 *
 * <p>嵌套配置类的解析顺序跟随 class 文件 InnerClasses 属性，与源码声明顺序无关
 * （javac 不保证保序），因此「Fs 优先 + Local 仅靠 @ConditionalOnMissingBean 兜底」
 * 会在 Local 先注册时整体失效（Fs 让位，图标静默落本地目录）。Local 侧必须显式
 * 声明「mse-fs 不可用」才兜底，与 Fs 侧条件互斥，注册顺序不再影响结果。
 */
public class WorkbenchIconUnavailableCondition implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !new WorkbenchIconAvailableCondition().matches(context, metadata);
    }
}
