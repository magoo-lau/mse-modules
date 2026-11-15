package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 数据服务调用方应用：appKey 全局唯一（开放调用按 appKey 定位租户与凭据）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_api_app")
public class DatApiApp extends BaseEntity {

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 0;
    /** 状态：停用 */
    public static final int STATUS_DISABLED = 1;

    /** 应用名称 */
    private String name;

    /** 应用 Key（全局唯一） */
    private String appKey;

    /** 应用密钥密文（AES-GCM，创建/重置后仅明文展示一次） */
    private String appSecret;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    private String remark;
}
