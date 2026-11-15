package vip.mseapp.monitor.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 监控服务键值配置（key=notify 告警通知设置、redis-slowlog-&lt;targetId&gt; 慢命令抓取开关，界面维护）。
 *
 * <p>config_value 为 JSON（NotifySetting / SlowCaptureSetting）。按租户隔离：平台端配置存 tenant_id=0 行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mon_setting")
public class MonSetting extends BaseEntity {

    /** 告警通知设置 */
    public static final String KEY_NOTIFY = "notify";

    /** Redis 慢命令抓取开关前缀（整键 = 前缀 + targetId，每目标一行） */
    public static final String KEY_REDIS_SLOWLOG_PREFIX = "redis-slowlog-";

    /** 配置键（notify / redis-slowlog-<targetId>） */
    private String configKey;

    /** 配置值 JSON */
    private String configValue;
}
