package vip.mseapp.ai.support;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import vip.mseapp.ai.service.ToolExecutionSupport;

/**
 * 工具执行 recorder 跨线程自动传播注册器（micrometer context-propagation 适配，
 * 机制同 common-core 的 TenantContextPropagation）。
 *
 * <p>用途：超限拦截发生在 ToolCallingManager 内部（不经过 ToolCallback 包装层，
 * {@code ToolExecutionListener} 看不见），只有 {@code FriendlyToolCallingManager}
 * 改写超限文案的位置能感知。对话链路在 {@code AiChatService} 把 recorder 经
 * {@code contextWrite} 放入 Reactor 上下文后，starter-web 已开启的自动传播
 * （{@code Hooks.enableAutomaticContextPropagation()}）会在模型内部工具循环线程
 * 恢复本 ThreadLocal，{@code FriendlyToolCallingManager} 即可取到 recorder 补录
 * 超限轨迹（phase=limit 事件 + ext 步骤）。传播未覆盖的线程取到 null——降级为仅日志。
 *
 * <p>装配由 ai-service 的 {@code AiServiceAutoConfiguration} 注册（幂等）。
 */
public final class ToolTracePropagation {

    /** 传播键（context-propagation 注册表内标识） */
    public static final String KEY = "mse.ai.toolRecorder";

    private static final ThreadLocal<ToolExecutionSupport.ToolExecutionRecorder> HOLDER = new ThreadLocal<>();

    private static volatile boolean registered;

    private ToolTracePropagation() {
    }

    /** 幂等注册（重复调用无副作用） */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        ContextRegistry.getInstance().registerThreadLocalAccessor(new RecorderAccessor());
        registered = true;
    }

    /** 当前线程活跃的 recorder（同步对话链路 try/finally 显式设置；异步链路经 Reactor 上下文自动恢复） */
    public static ToolExecutionSupport.ToolExecutionRecorder current() {
        return HOLDER.get();
    }

    public static void set(ToolExecutionSupport.ToolExecutionRecorder recorder) {
        HOLDER.set(recorder);
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** recorder 适配器：null 语义统一为「清空本线程 recorder」（restore 空值走 reset） */
    private static final class RecorderAccessor implements ThreadLocalAccessor<ToolExecutionSupport.ToolExecutionRecorder> {

        @Override
        public Object key() {
            return KEY;
        }

        @Override
        public ToolExecutionSupport.ToolExecutionRecorder getValue() {
            return HOLDER.get();
        }

        @Override
        public void setValue(ToolExecutionSupport.ToolExecutionRecorder value) {
            HOLDER.set(value);
        }

        @Override
        public void reset() {
            HOLDER.remove();
        }
    }
}
