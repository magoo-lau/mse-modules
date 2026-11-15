package vip.mseapp.monitor.engine.probe;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.monitor.config.MonitorProperties;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.support.ActuatorAuth;
import vip.mseapp.monitor.support.SimpleHttp;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 慢 SQL 探测：拉目标服务 /actuator/slowsql（框架 starter-mybatis 内置端点）。
 *
 * <p>enabled=false 表示服务侧未开启 mse.mybatis.slow-sql.enabled，上报 slowsql_enabled=否
 * 而非误报 0 条慢 SQL；HTTP 失败（含 404 未对齐暴露配置）才判 down。
 */
@Component
public class SlowSqlProbe implements Probe {

    /** 每次采样最多落库的最近慢 SQL 条数 */
    private static final int MAX_LIST = 5;

    /** 落库 SQL 文本上限（字符） */
    private static final int MAX_SQL_CHARS = 300;

    /** targetId → [上次累计慢SQL数, 上次采样 epoch ms] */
    private static final Map<Long, long[]> TOTAL_CACHE = new ConcurrentHashMap<>();

    private final JsonMapper jsonMapper;
    private final MonitorProperties properties;

    public SlowSqlProbe(JsonMapper jsonMapper, MonitorProperties properties) {
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    @Override
    public boolean supports(String targetType) {
        return MonTarget.TYPE_SLOWSQL.equals(targetType);
    }

    @Override
    public ProbeResult probe(MonTarget target, int timeoutMs) {
        String base = target.getServiceUrl();
        if (base == null || base.isBlank()) {
            return ProbeResult.down("服务地址未配置");
        }
        String url = (base.endsWith("/") ? base : base + "/") + "actuator/slowsql";
        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response =
                    SimpleHttp.get(url, timeoutMs, ActuatorAuth.headers(properties.getActuatorToken()));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode()
                        + (response.statusCode() == 404 ? "（slowsql 端点不可达：检查服务 exposure 配置是否含 slowsql）" : ""));
            }
            Map<?, ?> body = jsonMapper.readValue(response.body(), Map.class);
            boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("slowsql_enabled", enabled ? "是" : "否");
            if (!enabled) {
                TOTAL_CACHE.remove(target.getId());
                return ProbeResult.up((int) (System.currentTimeMillis() - start), metrics);
            }
            double total = body.get("totalSlow") instanceof Number number ? number.doubleValue() : Double.NaN;
            if (Double.isNaN(total)) {
                return ProbeResult.up((int) (System.currentTimeMillis() - start), metrics);
            }
            metrics.put("slowsql_total", (long) total);
            long now = System.currentTimeMillis();
            long[] prev = TOTAL_CACHE.get(target.getId());
            if (prev != null && now > prev[1] + 4_000 && total >= prev[0]) {
                metrics.put("slowsql_rate", total - prev[0]);
            }
            TOTAL_CACHE.put(target.getId(), new long[]{(long) total, now});
            putRecentSlowSqls(metrics, body.get("recent"));
            return ProbeResult.up((int) (System.currentTimeMillis() - start), metrics);
        } catch (Exception e) {
            TOTAL_CACHE.remove(target.getId());
            return ProbeResult.down(SimpleHttp.abbreviate(ServiceProbe.rootMessage(e)));
        }
    }

    /** 最近慢 SQL 截取前 MAX_LIST 条、SQL 截断后序列化为字符串指标，详情页渲染为表格 */
    private void putRecentSlowSqls(Map<String, Object> metrics, Object recent) {
        if (!(recent instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<Map<String, Object>> picked = new ArrayList<>();
        for (Object item : list) {
            if (picked.size() >= MAX_LIST) {
                break;
            }
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", m.get("time"));
                row.put("elapsedMs", m.get("elapsedMs"));
                row.put("mapperId", m.get("mapperId"));
                row.put("sql", abbreviate(String.valueOf(m.get("sql")), MAX_SQL_CHARS));
                picked.add(row);
            }
        }
        if (!picked.isEmpty()) {
            metrics.put("slow_sqls", jsonMapper.writeValueAsString(picked));
        }
    }

    private static String abbreviate(String sql, int max) {
        if (sql == null) {
            return "";
        }
        return sql.length() <= max ? sql : sql.substring(0, max) + "...";
    }
}
