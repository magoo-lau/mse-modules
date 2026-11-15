package vip.mseapp.open.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 用户扩展属性（租户表，租户插件自动隔离）。
 *
 * <p>JSON 字段承载各接入系统的差异化用户画像，避免改动基座 sys_user 表结构；
 * 一个用户一条记录，整体读写（PUT 语义为全量覆盖）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("open_user_extend")
public class OpenUserExtend extends BaseEntity {

    /** 中台用户编号（租户内唯一） */
    private Long userId;

    /** 扩展属性 JSON（对象字面量，如 {"grade":"A","vipLevel":3}） */
    private String extendJson;
}
