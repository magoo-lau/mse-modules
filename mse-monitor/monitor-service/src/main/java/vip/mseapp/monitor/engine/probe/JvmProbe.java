package vip.mseapp.monitor.engine.probe;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.monitor.config.MonitorProperties;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.support.ActuatorAuth;
import vip.mseapp.monitor.support.SimpleHttp;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM 探测：拉目标服务 /actuator/metrics 的 JVM 指标（零侵入，服务侧仅要求暴露 metrics 端点）。
 *
 * <p>堆/非堆内存、线程数、进程 CPU；GC 为累计值，按 MysqlProbe 同款进程内差值缓存换算
 * 每采集间隔增量，重启后首轮无增量值，不影响状态判定。
 */
@Component
public class JvmProbe implements Probe {

    private static final double MB = 1024.0 * 1024.0;

    /** targetId → [上次 GC 次数, 上次 GC 总耗时(秒), 上次采样 epoch ms] */
    private static final Map<Long, double[]> GC_CACHE = new ConcurrentHashMap<>();

    private final JsonMapper jsonMapper;
    private final MonitorProperties properties;

    public JvmProbe(JsonMapper jsonMapper, MonitorProperties properties) {
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    @Override
    public boolean supports(String targetType) {
        return MonTarget.TYPE_JVM.equals(targetType);
    }

    @Override
    public ProbeResult probe(MonTarget target, int timeoutMs) {
        String base = baseOf(target);
        if (base == null) {
            return ProbeResult.down("服务地址未配置");
        }
        Map<String, String> auth = ActuatorAuth.headers(properties.getActuatorToken());
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> metrics = new LinkedHashMap<>();
            // 首个指标兼作可达性判定：404 = 端点未暴露，连接失败 = 服务不可达
            double heapUsed = fetch(base, "jvm.memory.used", "area:heap", timeoutMs, auth);
            metrics.put("heap_used_mb", round(heapUsed / MB));
            double heapMax = fetch(base, "jvm.memory.max", "area:heap", timeoutMs, auth);
            if (heapMax > 0) {
                metrics.put("heap_max_mb", round(heapMax / MB));
                metrics.put("heap_usage_pct", round(heapUsed / heapMax * 100));
            }
            putIfFinite(metrics, "nonheap_used_mb",
                    tryFetch(base, "jvm.memory.used", "area:nonheap", timeoutMs, auth) / MB);
            putIfFinite(metrics, "threads_live", tryFetch(base, "jvm.threads.live", null, timeoutMs, auth));
            putIfFinite(metrics, "process_cpu_pct",
                    tryFetch(base, "process.cpu.usage", null, timeoutMs, auth) * 100);
            collectGc(target.getId(), base, timeoutMs, auth, metrics);
            return ProbeResult.up((int) (System.currentTimeMillis() - start), metrics);
        } catch (Exception e) {
            GC_CACHE.remove(target.getId());
            return ProbeResult.down(SimpleHttp.abbreviate(ServiceProbe.rootMessage(e)));
        }
    }

    /** GC 计时器：COUNT/TOTAL_TIME 均为进程启动累计值，差值换算本采集间隔的次数与耗时 */
    private void collectGc(Long targetId, String base, int timeoutMs, Map<String, String> auth,
                           Map<String, Object> metrics) {
        Map<String, Double> timer;
        try {
            timer = timerValues(base, "jvm.gc.pause", timeoutMs, auth);
        } catch (Exception e) {
            return;
        }
        double count = timer.getOrDefault("COUNT", Double.NaN);
        double totalSec = timer.getOrDefault("TOTAL_TIME", Double.NaN);
        if (Double.isNaN(count) || Double.isNaN(totalSec)) {
            return;
        }
        long now = System.currentTimeMillis();
        double[] prev = GC_CACHE.get(targetId);
        if (prev != null && now > prev[2] + 4_000 && count >= prev[0] && totalSec >= prev[1]) {
            metrics.put("gc_pause_count", (long) (count - prev[0]));
            metrics.put("gc_pause_time_ms", round((totalSec - prev[1]) * 1000));
        }
        GC_CACHE.put(targetId, new double[]{count, totalSec, now});
    }

    private double fetch(String base, String name, String tag, int timeoutMs, Map<String, String> auth) throws Exception {
        HttpResponse<String> response = SimpleHttp.get(metricUrl(base, name, tag), timeoutMs, auth);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode()
                    + (response.statusCode() == 404 ? "（metrics 端点未暴露？检查 management.endpoints.web.exposure.include）" : ""));
        }
        return gaugeValue(response.body());
    }

    private double tryFetch(String base, String name, String tag, int timeoutMs, Map<String, String> auth) {
        try {
            return fetch(base, name, tag, timeoutMs, auth);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private Map<String, Double> timerValues(String base, String name, int timeoutMs, Map<String, String> auth) throws Exception {
        HttpResponse<String> response = SimpleHttp.get(metricUrl(base, name, null), timeoutMs, auth);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Map.of();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        if (jsonMapper.readValue(response.body(), Map.class) instanceof Map<?, ?> body
                && body.get("measurements") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && m.get("statistic") instanceof String statistic
                        && m.get("value") instanceof Number value) {
                    result.put(statistic, value.doubleValue());
                }
            }
        }
        return result;
    }

    private double gaugeValue(String body) {
        if (jsonMapper.readValue(body, Map.class) instanceof Map<?, ?> map
                && map.get("measurements") instanceof List<?> list
                && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first
                && first.get("value") instanceof Number value) {
            return value.doubleValue();
        }
        return Double.NaN;
    }

    private String metricUrl(String base, String name, String tag) {
        return base + "/actuator/metrics/" + name + (tag == null ? "" : "?tag=" + tag);
    }

    private String baseOf(MonTarget target) {
        String url = target.getServiceUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void putIfFinite(Map<String, Object> metrics, String key, double value) {
        if (!Double.isNaN(value) && !Double.isInfinite(value)) {
            metrics.put(key, round(value));
        }
    }

    private double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
