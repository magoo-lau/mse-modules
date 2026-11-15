package vip.mseapp.ai.service;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import vip.mseapp.core.context.TenantContext;

import java.util.List;

/**
 * 租户绑定的 ChatMemory 每请求包装：构建时闭包携带租户号，全部读写经
 * {@link TenantContext#runAs} 锚定世界——彻底绕开 Reactor 传播语义的不确定性
 * （spring-ai 的 MessageChatMemoryAdvisor 在链内调度线程回调本接口，订阅线程快照
 * 与 contextWrite 在部分链路形态下不生效；显式 runAs 为确定性兜底，与框架传播叠加双保险）。
 *
 * <p>由 {@link AgentRuntime#buildClient} 在请求线程组装（tenantId 取自 ToolInvocationContext）；
 * 会话删除等请求线程直调仍走单例 {@link DbChatMemory}（自带上下文）。
 */
public class TenantBoundChatMemory implements ChatMemory {

    private final long tenantId;
    private final ChatMemory delegate;

    public TenantBoundChatMemory(long tenantId, ChatMemory delegate) {
        this.tenantId = tenantId;
        this.delegate = delegate;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        TenantContext.runAs(tenantId, () -> delegate.add(conversationId, messages));
    }

    @Override
    public List<Message> get(String conversationId) {
        return TenantContext.runAs(tenantId, () -> delegate.get(conversationId));
    }

    @Override
    public void clear(String conversationId) {
        TenantContext.runAs(tenantId, () -> delegate.clear(conversationId));
    }
}
