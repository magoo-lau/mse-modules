package vip.mseapp.open.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 统一身份绑定（租户表，租户插件自动隔离）。
 *
 * <p>外部系统的用户标识（sourceSystem + externalUserId）与中台账号（userId）的映射关系，
 * 供接入方解析"自家用户是谁"。同一外部标识在同一租户内唯一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("open_user_bind")
public class OpenUserBind extends BaseEntity {

    /** 中台用户编号（sys_user.id） */
    private Long userId;

    /** 来源系统标识（接入方自定，如 oa/hr/scrm） */
    private String sourceSystem;

    /** 外部系统用户标识 */
    private String externalUserId;

    private String remark;
}
