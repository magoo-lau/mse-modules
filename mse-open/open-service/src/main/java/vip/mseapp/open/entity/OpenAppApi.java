package vip.mseapp.open.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 应用-开放 API 授权关系（全局表）。应用仅能调用此处授权的 API。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("open_app_api")
public class OpenAppApi extends BaseEntity {

    /** 应用编号（open_app.id） */
    private Long appId;

    /** API 编号（open_api.id） */
    private Long apiId;
}
