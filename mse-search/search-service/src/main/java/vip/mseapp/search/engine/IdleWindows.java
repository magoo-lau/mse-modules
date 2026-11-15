package vip.mseapp.search.engine;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 闲时窗口解析（"HH:mm-HH:mm" 逗号分段，支持跨天如 22:00-06:00）。
 * 闲时专用任务（全量重建/索引优化）仅在窗口内执行，工作时间触发则保持待执行。
 */
public final class IdleWindows {

    private record Segment(LocalTime start, LocalTime end) {
    }

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final List<Segment> segments = new ArrayList<>();

    public IdleWindows(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        for (String part : expression.split(",")) {
            String segment = part.trim();
            int dash = segment.indexOf('-');
            if (dash <= 0 || dash == segment.length() - 1) {
                continue;
            }
            try {
                segments.add(new Segment(LocalTime.parse(segment.substring(0, dash).trim(), FORMAT),
                        LocalTime.parse(segment.substring(dash + 1).trim(), FORMAT)));
            } catch (Exception ignored) {
                // 非法分段跳过，配置错误不应阻断调度
            }
        }
    }

    /** 窗口为空视为"任意时刻皆闲时"（不阻塞任务） */
    public boolean isEmpty() {
        return segments.isEmpty();
    }

    public boolean contains(LocalTime time) {
        for (Segment segment : segments) {
            if (segment.start().isAfter(segment.end())) {
                // 跨天段（如 22:00-06:00）：不早于起点或不晚于终点
                if (!time.isBefore(segment.start()) || !time.isAfter(segment.end())) {
                    return true;
                }
            } else if (!time.isBefore(segment.start()) && !time.isAfter(segment.end())) {
                return true;
            }
        }
        return false;
    }
}
