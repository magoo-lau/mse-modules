package vip.mseapp.monitor.engine.probe;

import java.util.Map;

/**
 * 单次探测结果：状态 + 耗时 + 指标集（snake_case 键，前端直接消费）。
 */
public class ProbeResult {

    private final String status;
    private final Integer latencyMs;
    private final String error;
    private final Map<String, Object> metrics;

    private ProbeResult(String status, Integer latencyMs, String error, Map<String, Object> metrics) {
        this.status = status;
        this.latencyMs = latencyMs;
        this.error = error;
        this.metrics = metrics;
    }

    public static ProbeResult up(int latencyMs, Map<String, Object> metrics) {
        return new ProbeResult("up", latencyMs, null, metrics);
    }

    public static ProbeResult down(String error) {
        return new ProbeResult("down", null, error, Map.of());
    }

    public String getStatus() {
        return status;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public String getError() {
        return error;
    }

    public Map<String, Object> getMetrics() {
        return metrics;
    }
}
