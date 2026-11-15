package vip.mseapp.im.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * IM 机器人绑定（bot 账号 ↔ mse-ai 智能体）：bot 即 OpenIM 普通用户（bot_ 前缀），
 * 消息回调命中后经智能体生成回复，管理端 /msg/send_msg 代发。
 */
@TableName("im_bot")
public class ImBot extends BaseEntity {

    /** 租户编号 */
    private Long tenantId;

    /** OpenIM userID（bot_ 前缀，实例内唯一） */
    private String botUserId;

    /** 展示昵称（注册进 IM） */
    private String nickname;

    /** 头像 URL（可空） */
    private String faceUrl;

    /** 绑定的智能体编码（ai_agent.agent_code，必填） */
    private String agentCode;

    /** 触发关键词（逗号分隔；空 = 群内 @ 即触发；单聊直发始终触发） */
    private String triggerKeywords;

    /** 群白名单（逗号分隔 groupID；空 = 所有群） */
    private String groupIds;

    /** 0 启用 1 停用 */
    private Integer status;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getBotUserId() {
        return botUserId;
    }

    public void setBotUserId(String botUserId) {
        this.botUserId = botUserId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getFaceUrl() {
        return faceUrl;
    }

    public void setFaceUrl(String faceUrl) {
        this.faceUrl = faceUrl;
    }

    public String getAgentCode() {
        return agentCode;
    }

    public void setAgentCode(String agentCode) {
        this.agentCode = agentCode;
    }

    public String getTriggerKeywords() {
        return triggerKeywords;
    }

    public void setTriggerKeywords(String triggerKeywords) {
        this.triggerKeywords = triggerKeywords;
    }

    public String getGroupIds() {
        return groupIds;
    }

    public void setGroupIds(String groupIds) {
        this.groupIds = groupIds;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
