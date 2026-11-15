package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.core.annotation.TenantDatasource;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 对话会话（租户业务表）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantDatasource
@TableName("ai_chat_session")
public class AiChatSession extends BaseEntity {

    /** 会话标题（首条消息截断生成，可改名） */
    private String title;

    /** 智能体编码 */
    private String agentCode;

    /** 所属用户 */
    private Long userId;

    /** 最近活跃时间 */
    private java.time.LocalDateTime lastActiveTime;
}
