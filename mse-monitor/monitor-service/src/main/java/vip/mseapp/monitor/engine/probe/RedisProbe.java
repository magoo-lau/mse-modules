package vip.mseapp.monitor.engine.probe;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.monitor.config.MonitorProperties;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.service.SlowCaptureService;
import vip.mseapp.monitor.support.SimpleHttp;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 探测：Lettuce 直连目标实例执行 INFO，解析内存/连接/命中率/OPS。
 *
 * <p>慢命令抓取（界面开关，mon_setting redis-slowlog-&lt;targetId&gt;）：仅在抓取窗口内追加两条
 * 只读命令 SLOWLOG GET/LEN（entry 为 [id, 时间戳秒, 耗时μs, argv, 客户端, 名称] 的嵌套回复，
 * 按下标解析），展示慢命令与涉及 key；非抓取时零额外开销。只读不改服务端 slowlog 配置。
 */
@Component
public class RedisProbe implements Probe {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SlowCaptureService slowCaptureService;
    private final MonitorProperties properties;
    private final JsonMapper jsonMapper;

    public RedisProbe(SlowCaptureService slowCaptureService, MonitorProperties properties, JsonMapper jsonMapper) {
        this.slowCaptureService = slowCaptureService;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean supports(String targetType) {
        return MonTarget.TYPE_REDIS.equals(targetType);
    }

    @Override
    public ProbeResult probe(MonTarget target, int timeoutMs) {
        String host = target.getHost();
        Integer port = target.getPort();
        if (host == null || host.isBlank() || port == null) {
            return ProbeResult.down("主机/端口未配置");
        }
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofMillis(timeoutMs));
        if (target.getPassword() != null && !target.getPassword().isBlank()) {
            builder.withPassword(target.getPassword());
        }
        RedisClient client = RedisClient.create(builder.build());
        long start = System.currentTimeMillis();
        try {
            RedisCommands<String, String> commands = client.connect().sync();
            try {
                int latency = (int) (System.currentTimeMillis() - start);
                Map<String, Object> metrics = parseInfo(commands.info());
                collectSlowCmds(target, commands, metrics);
                return ProbeResult.up(latency, metrics);
            } finally {
                client.shutdown();
            }
        } catch (Exception e) {
            client.shutdown();
            return ProbeResult.down(SimpleHttp.abbreviate(ServiceProbe.rootMessage(e)));
        }
    }

    /** 慢命令抓取：窗口内读 SLOWLOG，读取失败不影响主探测结果 */
    private void collectSlowCmds(MonTarget target, RedisCommands<String, String> commands,
                                 Map<String, Object> metrics) {
        if (!slowCaptureService.capturing(target.getId())) {
            metrics.put("slowcmd_capturing", "否");
            return;
        }
        metrics.put("slowcmd_capturing", "是");
        try {
            Long len = commands.slowlogLen();
            if (len != null) {
                metrics.put("slowcmd_len", len);
            }
            List<Object> entries = commands.slowlogGet(properties.getRedis().getSlowlogMaxEntries());
            List<Map<String, Object>> rows = parseSlowlog(entries);
            if (!rows.isEmpty()) {
                metrics.put("slow_cmds", jsonMapper.writeValueAsString(rows));
            }
        } catch (Exception ignored) {
            // SLOWLOG 仅附加诊断信息，失败不改变目标 up/down 判定
        }
    }

    /** entry 按下标解析：[id, 时间戳秒, 耗时μs, argv 列表, 客户端地址, 客户端名]，key 取 argv[1] */
    private List<Map<String, Object>> parseSlowlog(List<Object> entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (entries == null) {
            return rows;
        }
        for (Object item : entries) {
            if (!(item instanceof List<?> fields) || fields.size() < 4) {
                continue;
            }
            long epochSecond = fields.get(1) instanceof Number n ? n.longValue() : 0;
            long micros = fields.get(2) instanceof Number n ? n.longValue() : 0;
            List<?> argv = fields.get(3) instanceof List<?> args ? args : List.of();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", epochSecond > 0
                    ? TIME_FORMAT.format(Instant.ofEpochSecond(epochSecond).atZone(ZoneId.systemDefault())) : "");
            row.put("elapsedMs", Math.round(micros / 100.0) / 10.0);
            row.put("command", argv.isEmpty() ? "?" : String.valueOf(argv.get(0)).toUpperCase());
            row.put("key", argv.size() > 1 ? abbreviate(String.valueOf(argv.get(1))) : "-");
            row.put("args", abbreviate(joinArgs(argv)));
            rows.add(row);
        }
        return rows;
    }

    private String joinArgs(List<?> argv) {
        StringBuilder sb = new StringBuilder();
        for (Object arg : argv) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(arg);
        }
        return sb.toString();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private Map<String, Object> parseInfo(String info) {
        Map<String, String> kv = new LinkedHashMap<>();
        if (info != null) {
            for (String line : info.split("\\r?\\n")) {
                int idx = line.indexOf(':');
                if (idx > 0 && !line.startsWith("#")) {
                    kv.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        double usedMemory = parseDouble(kv.get("used_memory"));
        double maxMemory = parseDouble(kv.get("maxmemory"));
        metrics.put("used_memory_mb", round1(usedMemory / 1048576));
        if (maxMemory > 0) {
            metrics.put("maxmemory_mb", round1(maxMemory / 1048576));
            metrics.put("mem_usage_pct", round1(usedMemory * 100 / maxMemory));
        }
        putIfPresent(metrics, "connected_clients", kv.get("connected_clients"));
        putIfPresent(metrics, "ops_per_sec", kv.get("instantaneous_ops_per_sec"));
        double hits = parseDouble(kv.get("keyspace_hits"));
        double misses = parseDouble(kv.get("keyspace_misses"));
        if (hits + misses > 0) {
            metrics.put("hit_rate_pct", round1(hits * 100 / (hits + misses)));
        }
        putIfPresent(metrics, "redis_version", kv.get("redis_version"));
        return metrics;
    }

    private void putIfPresent(Map<String, Object> metrics, String key, String value) {
        if (value != null) {
            try {
                metrics.put(key, Long.parseLong(value));
            } catch (NumberFormatException e) {
                metrics.put(key, value);
            }
        }
    }

    private double parseDouble(String value) {
        try {
            return value == null ? 0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
