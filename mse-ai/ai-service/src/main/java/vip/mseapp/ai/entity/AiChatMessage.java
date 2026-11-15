package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.core.annotation.TenantDatasource;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 对话消息（租户业务表，按 session_id 归档；作为 Spring AI ChatMemory 的持久化实现）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantDatasource
@TableName("ai_chat_message")
public class AiChatMessage extends BaseEntity {

    /** 会话 ID（ai_chat_session.id 的字符串形态，即 ChatMemory conversationId） */
    private String sessionId;

    /** 角色：user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 扩展信息 JSON（assistant 消息：datasets 工具数据结果 / sources 引用来源；历史回放恢复用） */
    private String ext;
}
