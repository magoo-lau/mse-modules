package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;

/**
 * 数据服务 API 授权（API ↔ 调用方应用，未授权应用不可调用）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_api_grant")
public class DatApiGrant extends BaseEntity {

    /** API 编号 */
    private Long apiId;

    /** 应用编号（dat_api_app.id） */
    private Long appId;

    /** 过期时间（空=永久） */
    private LocalDateTime expireTime;
}
