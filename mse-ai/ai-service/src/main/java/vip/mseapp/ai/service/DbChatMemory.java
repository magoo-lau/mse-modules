package vip.mseapp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.entity.AiChatMessage;
import vip.mseapp.ai.entity.AiChatSession;
import vip.mseapp.ai.mapper.AiChatMessageMapper;
import vip.mseapp.ai.mapper.AiChatSessionMapper;

import java.util.List;

/**
 * 落库 ChatMemory：Spring AI {@link ChatMemory} 的 MySQL 实现（对话历史可审计、可回放）。
 *
 * <p>conversationId 即 ai_chat_session.id 字符串。消息表按 session_id（雪花唯一键）
 * 定位，读写随环境租户上下文路由（一切皆租户）：本类运行在 Reactor 线程时租户经框架
 * 自动传播恢复（starter-web Reactor 自动传播），直接调用（请求线程）自带上下文；
 * 租户归属由会话实体携带并在写入时显式赋值。
 *
 * <p>上下文窗口在 {@link #get(String)} 内截断（最近 N 条），不使用
 * MessageWindowChatMemory 包装——避免其“每轮 clear+重写 N 行”的落库放大。
 * 只保留 user/assistant 文本消息（工具调用中间消息不入库，审计见 ai_tool_log）。
 */
@Component
public class DbChatMemory implements ChatMemory {

    private final AiChatSessionMapper sessionMapper;
    private final AiChatMessageMapper messageMapper;
    private final AiPlatformProperties properties;

    public DbChatMemory(AiChatSessionMapper sessionMapper, AiChatMessageMapper messageMapper,
                        AiPlatformProperties properties) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.properties = properties;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        AiChatSession session = requireSession(conversationId);
        for (Message message : messages) {
            String role;
            String content;
            if (message instanceof UserMessage userMessage) {
                role = "user";
                content = userMessage.getText();
            } else if (message instanceof AssistantMessage assistantMessage
                    && !assistantMessage.hasToolCalls()) {
                role = "assistant";
                content = assistantMessage.getText();
            } else {
                // 系统消息每次由智能体提示词现场渲染；工具调用中间消息不入历史
                continue;
            }
            if (content == null || content.isBlank()) {
                continue;
            }
            AiChatMessage record = new AiChatMessage();
            record.setSessionId(conversationId);
            record.setRole(role);
            record.setContent(content);
            record.setTenantId(session.getTenantId());
            messageMapper.insert(record);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        requireSession(conversationId);
        int window = Math.max(properties.getMemoryWindow(), 2);
        List<AiChatMessage> records = messageMapper.selectList(new LambdaQueryWrapper<AiChatMessage>()
                .eq(AiChatMessage::getSessionId, conversationId)
                .orderByAsc(AiChatMessage::getId));
        return records.stream()
                .skip(Math.max(0, records.size() - window))
                .map(r -> "user".equals(r.getRole())
                        ? (Message) new UserMessage(r.getContent())
                        : (Message) new AssistantMessage(r.getContent()))
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        messageMapper.delete(new LambdaQueryWrapper<AiChatMessage>()
                .eq(AiChatMessage::getSessionId, conversationId));
    }

    /** 按会话清空消息并保留会话骨架 */
    public AiChatSession requireSession(String conversationId) {
        AiChatSession session = sessionMapper.selectById(Long.valueOf(conversationId));
        if (session == null) {
            throw new vip.mseapp.core.exception.ServiceException(404, "会话不存在: " + conversationId);
        }
        return session;
    }
}
