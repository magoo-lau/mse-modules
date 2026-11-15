package vip.mseapp.im.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * IM 用户独立资料（当前仅头像）：face_url 非空即 IM 侧自设头像（优先于基座 sys_user.avatar），
 * 空串/无行 = 回落基座头像，两级都空由客户端首字母兜底。行惰性创建，重置头像置空不删行。
 */
@TableName("im_user_profile")
public class ImUserProfile extends BaseEntity {

    private Long tenantId;

    /** 用户编号（sys_user.id，即 OpenIM userID） */
    private Long userId;

    /** IM 自设头像地址（站内相对路径或 http(s) 绝对地址；空串=未自设） */
    private String faceUrl;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getFaceUrl() {
        return faceUrl;
    }

    public void setFaceUrl(String faceUrl) {
        this.faceUrl = faceUrl;
    }
}
