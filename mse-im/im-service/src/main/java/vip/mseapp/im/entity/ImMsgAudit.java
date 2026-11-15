package vip.mseapp.im.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * IM 消息外发审计（webhook afterSend 类事件全量异步落库，机器人链路之外）。
 */
@TableName("im_msg_audit")
public class ImMsgAudit extends BaseEntity {

    /** 租户编号 */
    private Long tenantId;

    /** 消息 ID（clientMsgID） */
    private String msgId;

    /** 发送者 OpenIM userID */
    private String sendId;

    /** 单聊接收者 */
    private String recvId;

    /** 群 ID */
    private String groupId;

    /** 1 单聊 3 群聊 */
    private Integer sessionType;

    /** 消息内容类型（101 文本 / 106 AtText 等） */
    private Integer contentType;

    /** 文本摘要（截断 1000） */
    private String contentText;

    /** 发送者昵称快照（webhook sendNickname；机器人上下文渲染用，历史行 NULL 回落 send_id） */
    private String senderNickname;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public String getSendId() {
        return sendId;
    }

    public void setSendId(String sendId) {
        this.sendId = sendId;
    }

    public String getRecvId() {
        return recvId;
    }

    public void setRecvId(String recvId) {
        this.recvId = recvId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Integer getSessionType() {
        return sessionType;
    }

    public void setSessionType(Integer sessionType) {
        this.sessionType = sessionType;
    }

    public Integer getContentType() {
        return contentType;
    }

    public void setContentType(Integer contentType) {
        this.contentType = contentType;
    }

    public String getContentText() {
        return contentText;
    }

    public void setContentText(String contentText) {
        this.contentText = contentText;
    }

    public String getSenderNickname() {
        return senderNickname;
    }

    public void setSenderNickname(String senderNickname) {
        this.senderNickname = senderNickname;
    }
}
