package vip.mseapp.monitor.support;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Redis 慢命令抓取开关（mon_setting key=redis-slowlog-&lt;targetId&gt; 的 JSON 视图）。
 *
 * <p>时间字段用字符串（yyyy-MM-dd HH:mm:ss）存取，规避 JSON 日期序列化格式歧义；
 * endAt 为空表示持续开启直到手动关闭，抓取中 = enabled 且当前时间在窗口内。
 */
public class SlowCaptureSetting {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 是否开启抓取（过期窗口读取时按未开启处理） */
    private boolean enabled;

    /** 抓取窗口开始（yyyy-MM-dd HH:mm:ss，空 = 随开启时间） */
    private String startAt;

    /** 抓取窗口结束（空 = 持续开启直到手动关闭） */
    private String endAt;

    public static SlowCaptureSetting disabled() {
        return new SlowCaptureSetting();
    }

    /** 当前是否处于抓取窗口内（解析失败按未开启，不影响探测主流程） */
    public boolean capturingNow() {
        if (!enabled) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = parse(startAt);
        LocalDateTime end = parse(endAt);
        return (start == null || !now.isBefore(start)) && (end == null || !now.isAfter(end));
    }

    private static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStartAt() {
        return startAt;
    }

    public void setStartAt(String startAt) {
        this.startAt = startAt;
    }

    public String getEndAt() {
        return endAt;
    }

    public void setEndAt(String endAt) {
        this.endAt = endAt;
    }
}
