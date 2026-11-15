package vip.mseapp.monitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.monitor.config.MonitorProperties;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.mapper.MonTargetMapper;
import vip.mseapp.monitor.support.MonitorWorld;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Redis 缓存监控：Lettuce 按请求直连 mon_target 中 type=redis 的目标实例（连接参数与 RedisProbe 同源），
 * 提供 INFO 概览、键浏览（键名全集供前端构建冒号键树 + 分页平铺接口保留）、键值预览与删键。
 *
 * <p>同一「缓存监控」页承载浏览与删键；删键由独立权限点 {@code monitor:cache:delete} 管控。
 */
@Service
public class RedisCacheService {

    /** SCAN 每批提示数量 */
    private static final int SCAN_COUNT = 500;
    /** SCAN 迭代硬上限（约 100 万键的 examined 上限，防窄匹配拖垮实例） */
    private static final int MAX_SCAN_LOOPS = 2_000;
    /** string 值预览最大字符数 */
    private static final int VALUE_CHAR_LIMIT = 64_000;
    /** 集合类型值预览条数上限 */
    private static final int VALUE_ITEM_LIMIT = 200;
    /** 单次键浏览最多返回给前端的条数 */
    private static final int PAGE_SIZE_LIMIT = 200;

    private final MonTargetMapper targetMapper;
    private final MonitorProperties properties;
    private final JsonMapper jsonMapper;
    private final MonitorWorld world;

    public RedisCacheService(MonTargetMapper targetMapper, MonitorProperties properties,
                             JsonMapper jsonMapper, MonitorWorld world) {
        this.targetMapper = targetMapper;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.world = world;
    }

    // ---------------- 响应模型 ----------------

    public record RedisTargetItem(Long id, String name, String host, Integer port, String status) {
    }

    public record RedisKeyItem(String name, String type, long ttlMs) {
    }

    /** capped=true 表示匹配键超出 scan-limit 被截断，total 为已收集数量（非全量） */
    public record KeyPage(long total, long pageNum, long pageSize, boolean capped, List<RedisKeyItem> records) {
    }

    /** 键名全集（scan-limit 内，前端按冒号分层构建键树；类型/TTL 不随全集下发，点击叶子按需查详情） */
    public record KeyNames(long total, boolean capped, List<String> names) {
    }

    /** SCAN 收集结果：finished=false 表示达到收集上限提前终止（capped） */
    private record Scanned(TreeSet<String> names, boolean finished) {
    }

    public record KeyDetail(String name, String type, long ttlMs, String encoding, Long memoryUsage,
                            Long length, boolean truncated, String valueKind, Object value) {
    }

    /** 请求级连接：Lettuce client 关闭即释放其全部连接 */
    private record TargetConnection(RedisClient client, RedisCommands<String, String> commands) implements AutoCloseable {
        @Override
        public void close() {
            client.shutdown();
        }
    }

    // ---------------- 对外能力 ----------------

    public List<RedisTargetItem> listTargets() {
        List<MonTarget> targets = world.scope(() -> targetMapper.selectList(
                new LambdaQueryWrapper<MonTarget>()
                        .eq(MonTarget::getTargetType, MonTarget.TYPE_REDIS)
                        .orderByAsc(MonTarget::getName)));
        return targets.stream()
                .map(t -> new RedisTargetItem(t.getId(), t.getName(), t.getHost(), t.getPort(), t.getStatus()))
                .toList();
    }

    public Map<String, Object> info(Long targetId, int db) {
        MonTarget target = loadTarget(targetId);
        try (TargetConnection conn = connect(target, db)) {
            RedisCommands<String, String> c = conn.commands();
            Map<String, String> kv = parseInfo(c.info());

            Map<String, Object> result = new LinkedHashMap<>();
            putIfPresent(result, "version", kv.get("redis_version"));
            putIfPresent(result, "os", kv.get("os"));
            putLong(result, "uptimeDays", kv.get("uptime_in_days"));
            putLong(result, "connectedClients", kv.get("connected_clients"));
            putLong(result, "opsPerSec", kv.get("instantaneous_ops_per_sec"));
            double usedMemory = parseDouble(kv.get("used_memory"));
            double maxMemory = parseDouble(kv.get("maxmemory"));
            result.put("usedMemoryMb", round1(usedMemory / 1048576));
            if (maxMemory > 0) {
                result.put("maxmemoryMb", round1(maxMemory / 1048576));
                result.put("memUsagePct", round1(usedMemory * 100 / maxMemory));
            }
            double hits = parseDouble(kv.get("keyspace_hits"));
            double misses = parseDouble(kv.get("keyspace_misses"));
            if (hits + misses > 0) {
                result.put("hitRatePct", round1(hits * 100 / (hits + misses)));
            }
            result.put("dbsize", c.dbsize());
            result.put("keyspace", parseKeyspace(kv));
            return result;
        }
    }

    public KeyPage keys(Long targetId, int db, String pattern, long pageNum, long pageSize) {
        MonTarget target = loadTarget(targetId);
        int pageNumSafe = (int) Math.max(pageNum, 1);
        int pageSizeSafe = (int) Math.min(Math.max(pageSize, 1), PAGE_SIZE_LIMIT);
        int scanLimit = Math.max(properties.getRedis().getScanLimit(), 100);
        String match = pattern == null || pattern.isBlank() ? "*" : pattern;

        try (TargetConnection conn = connect(target, db)) {
            RedisCommands<String, String> c = conn.commands();
            Scanned scanned = scanNames(c, match);
            List<String> sorted = new ArrayList<>(scanned.names());
            long total = Math.min(sorted.size(), (long) scanLimit);
            int from = (int) Math.min((long) (pageNumSafe - 1) * pageSizeSafe, total);
            int to = (int) Math.min(from + (long) pageSizeSafe, total);
            List<RedisKeyItem> items = new ArrayList<>(Math.max(to - from, 0));
            for (String name : sorted.subList(from, to)) {
                items.add(new RedisKeyItem(name, c.type(name), ttlOrUnknown(c.pttl(name))));
            }
            return new KeyPage(total, pageNumSafe, pageSizeSafe, !scanned.finished(), items);
        }
    }

    /** 键名全集（有序去重，截断到 scan-limit）；供前端按冒号分层构建键树，零逐键往返 */
    public KeyNames keyNames(Long targetId, int db, String pattern) {
        MonTarget target = loadTarget(targetId);
        int scanLimit = Math.max(properties.getRedis().getScanLimit(), 100);
        String match = pattern == null || pattern.isBlank() ? "*" : pattern;

        try (TargetConnection conn = connect(target, db)) {
            Scanned scanned = scanNames(conn.commands(), match);
            List<String> names = new ArrayList<>(scanned.names());
            if (names.size() > scanLimit) {
                names.subList(scanLimit, names.size()).clear();
            }
            return new KeyNames(names.size(), !scanned.finished(), names);
        }
    }

    public KeyDetail keyDetail(Long targetId, int db, String name) {
        MonTarget target = loadTarget(targetId);
        try (TargetConnection conn = connect(target, db)) {
            RedisCommands<String, String> c = conn.commands();
            String type = c.type(name);
            if (type == null || "none".equals(type)) {
                throw new ServiceException(404, "键不存在或已过期");
            }
            long ttl = ttlOrUnknown(c.pttl(name));
            String encoding = safe(() -> c.objectEncoding(name));
            Long memoryUsage = safe(() -> c.memoryUsage(name));
            return switch (type) {
                case "string" -> stringDetail(name, ttl, encoding, memoryUsage, c);
                case "hash" -> {
                    Long length = c.hlen(name);
                    Map<String, String> map = new TreeMap<>(c.hscan(name, ScanArgs.Builder.limit(VALUE_ITEM_LIMIT)).getMap());
                    yield new KeyDetail(name, type, ttl, encoding, memoryUsage, length,
                            length != null && length > VALUE_ITEM_LIMIT, "map", map);
                }
                case "list" -> {
                    Long length = c.llen(name);
                    List<String> list = c.lrange(name, 0, VALUE_ITEM_LIMIT - 1);
                    yield new KeyDetail(name, type, ttl, encoding, memoryUsage, length,
                            length != null && length > VALUE_ITEM_LIMIT, "array", list);
                }
                case "set" -> {
                    Long length = c.scard(name);
                    List<String> members = new ArrayList<>(c.sscan(name, ScanArgs.Builder.limit(VALUE_ITEM_LIMIT)).getValues());
                    Collections.sort(members);
                    yield new KeyDetail(name, type, ttl, encoding, memoryUsage, length,
                            length != null && length > VALUE_ITEM_LIMIT, "array", members);
                }
                case "zset" -> {
                    Long length = c.zcard(name);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (ScoredValue<String> sv : c.zrangeWithScores(name, 0, VALUE_ITEM_LIMIT - 1)) {
                        rows.add(Map.of("value", sv.getValue(), "score", sv.getScore()));
                    }
                    yield new KeyDetail(name, type, ttl, encoding, memoryUsage, length,
                            length != null && length > VALUE_ITEM_LIMIT, "scored", rows);
                }
                case "stream" -> {
                    Long length = c.xlen(name);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (StreamMessage<String, String> e : c.xrange(name, Range.unbounded(), Limit.from(VALUE_ITEM_LIMIT))) {
                        rows.add(Map.of("id", String.valueOf(e.getId()),
                                "fields", e.getBody() == null ? Map.of() : e.getBody()));
                    }
                    yield new KeyDetail(name, type, ttl, encoding, memoryUsage, length,
                            length != null && length > VALUE_ITEM_LIMIT, "stream", rows);
                }
                default -> new KeyDetail(name, type, ttl, encoding, memoryUsage, null, false, "unsupported", null);
            };
        }
    }

    /** @return 实际删除的键数量 */
    public long deleteKey(Long targetId, int db, String name) {
        MonTarget target = loadTarget(targetId);
        try (TargetConnection conn = connect(target, db)) {
            Long deleted = conn.commands().del(name);
            return deleted == null ? 0 : deleted;
        }
    }

    // ---------------- 内部实现 ----------------

    /**
     * SCAN 全量收集（scan-limit 条数硬上限 + MAX_SCAN_LOOPS 迭代环数硬上限双保护，防窄匹配拖垮实例）。
     * 连接由调用方持有并负责关闭。
     */
    private Scanned scanNames(RedisCommands<String, String> c, String match) {
        int scanLimit = Math.max(properties.getRedis().getScanLimit(), 100);
        TreeSet<String> names = new TreeSet<>();
        ScanArgs args = ScanArgs.Builder.matches(match).limit(SCAN_COUNT);
        KeyScanCursor<String> cursor = c.scan(ScanCursor.INITIAL, args);
        int loops = 0;
        while (names.size() < scanLimit + 1) {
            names.addAll(cursor.getKeys());
            if ("0".equals(cursor.getCursor()) || ++loops >= MAX_SCAN_LOOPS) {
                return new Scanned(names, "0".equals(cursor.getCursor()));
            }
            cursor = c.scan(cursor, args);
        }
        return new Scanned(names, false);
    }

    private KeyDetail stringDetail(String name, long ttl, String encoding, Long memoryUsage,
                                   RedisCommands<String, String> c) {
        String raw = c.get(name);
        if (raw == null) {
            throw new ServiceException(404, "键不存在或已过期");
        }
        Long length = safe(() -> c.strlen(name));
        boolean truncated = false;
        if (raw.length() > VALUE_CHAR_LIMIT) {
            raw = raw.substring(0, VALUE_CHAR_LIMIT);
            truncated = true;
        }
        String valueKind = "text";
        Object value = raw;
        String trimmed = raw.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                value = jsonMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(jsonMapper.readValue(trimmed, Object.class));
                valueKind = "json";
            } catch (Exception ignored) {
                // 非 JSON 按原文展示
            }
        } else if (looksBinary(raw)) {
            value = "（二进制数据，共 " + (length != null ? length : raw.length()) + " 字节，无法按文本展示）";
            valueKind = "binary";
        }
        return new KeyDetail(name, "string", ttl, encoding, memoryUsage, length, truncated, valueKind, value);
    }

    private MonTarget loadTarget(Long targetId) {
        MonTarget target = world.scope(() -> targetMapper.selectById(targetId));
        if (target == null) {
            throw new ServiceException(404, "监控目标不存在");
        }
        if (!MonTarget.TYPE_REDIS.equals(target.getTargetType())) {
            throw new ServiceException(400, "仅支持 Redis 类型的监控目标");
        }
        return target;
    }

    private TargetConnection connect(MonTarget target, int db) {
        if (target.getHost() == null || target.getHost().isBlank() || target.getPort() == null) {
            throw new ServiceException(400, "目标未配置主机/端口");
        }
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(target.getHost())
                .withPort(target.getPort())
                .withDatabase(db)
                .withTimeout(Duration.ofMillis(properties.getProbe().getTimeoutMs()));
        if (target.getPassword() != null && !target.getPassword().isBlank()) {
            builder.withPassword(target.getPassword());
        }
        RedisClient client = RedisClient.create(builder.build());
        try {
            return new TargetConnection(client, client.connect().sync());
        } catch (Exception e) {
            client.shutdown();
            throw new ServiceException(502, "Redis 连接失败：" + rootMessage(e));
        }
    }

    private Map<String, String> parseInfo(String info) {
        Map<String, String> kv = new LinkedHashMap<>();
        if (info != null) {
            for (String line : info.split("\\r?\\n")) {
                int idx = line.indexOf(':');
                if (idx > 0 && !line.startsWith("#")) {
                    kv.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }
        }
        return kv;
    }

    /** 解析 INFO keyspace 段：db0:keys=1,expires=0,avg_ttl=0 */
    private Map<String, Object> parseKeyspace(Map<String, String> kv) {
        Map<String, Object> keyspace = new TreeMap<>();
        for (Map.Entry<String, String> entry : kv.entrySet()) {
            if (!entry.getKey().matches("db\\d+")) {
                continue;
            }
            Map<String, Object> stat = new LinkedHashMap<>();
            for (String part : entry.getValue().split(",")) {
                int idx = part.indexOf('=');
                if (idx > 0) {
                    try {
                        stat.put(part.substring(0, idx).trim(), Long.parseLong(part.substring(idx + 1).trim()));
                    } catch (NumberFormatException ignored) {
                        // 非数字段跳过
                    }
                }
            }
            keyspace.put(entry.getKey(), stat);
        }
        return keyspace;
    }

    /** 内容包含 UTF-8 解码替换符或超比例控制字符时视为二进制 */
    private boolean looksBinary(String s) {
        if (s.indexOf('\uFFFD') >= 0) {
            return true;
        }
        int control = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                continue;
            }
            if (ch < 0x20) {
                control++;
            }
        }
        return control * 10 > s.length();
    }

    private long ttlOrUnknown(Long ttl) {
        return ttl == null ? -3 : ttl;
    }

    private <T> T safe(Supplier<T> call) {
        try {
            return call.get();
        } catch (Exception e) {
            return null;
        }
    }

    private void putIfPresent(Map<String, Object> result, String key, String value) {
        if (value != null) {
            result.put(key, value);
        }
    }

    private void putLong(Map<String, Object> result, String key, String value) {
        if (value != null) {
            try {
                result.put(key, Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // 非数字指标跳过
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

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
