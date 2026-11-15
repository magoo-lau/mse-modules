package vip.mseapp.monitor.engine.probe;

import org.springframework.stereotype.Component;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.support.SimpleHttp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MySQL 探测：单连接 SHOW GLOBAL STATUS/VARIABLES 取连接数与 Questions（差值估算 QPS）。
 *
 * <p>驱动由 micro/mono-starter 传递（三方言 JDBC 驱动），DriverManager SPI 自动注册；
 * QPS 缓存为进程内 Map，重启后首轮无 QPS 值，不影响状态判定。
 */
@Component
public class MysqlProbe implements Probe {

    /** targetId → [上次 Questions, 上次采样 epoch ms] */
    private static final Map<Long, long[]> QPS_CACHE = new ConcurrentHashMap<>();

    @Override
    public boolean supports(String targetType) {
        return MonTarget.TYPE_MYSQL.equals(targetType);
    }

    @Override
    public ProbeResult probe(MonTarget target, int timeoutMs) {
        String host = target.getHost();
        Integer port = target.getPort();
        if (host == null || host.isBlank() || port == null) {
            return ProbeResult.down("主机/端口未配置");
        }
        String user = target.getUsername() == null || target.getUsername().isBlank()
                ? "root" : target.getUsername();
        String password = target.getPassword() == null ? "" : target.getPassword();
        String url = "jdbc:mysql://" + host + ":" + port + "/?connectTimeout=" + timeoutMs
                + "&socketTimeout=" + timeoutMs + "&useSSL=false&allowPublicKeyRetrieval=true";
        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            int latency = (int) (System.currentTimeMillis() - start);
            Map<String, Object> metrics = new LinkedHashMap<>();
            long questions = queryNumeric(conn, "SHOW GLOBAL STATUS LIKE 'Questions'");
            long threads = queryNumeric(conn, "SHOW GLOBAL STATUS LIKE 'Threads_connected'");
            long maxConn = queryNumeric(conn, "SHOW GLOBAL VARIABLES LIKE 'max_connections'");
            metrics.put("threads_connected", threads);
            if (maxConn > 0) {
                metrics.put("max_connections", maxConn);
            }
            long[] prev = QPS_CACHE.get(target.getId());
            long now = System.currentTimeMillis();
            if (prev != null && now > prev[1] + 4_000) {
                double qps = (questions - prev[0]) * 1000.0 / (now - prev[1]);
                if (qps >= 0) {
                    metrics.put("qps", Math.round(qps * 10) / 10.0);
                }
            }
            QPS_CACHE.put(target.getId(), new long[]{questions, now});
            return ProbeResult.up(latency, metrics);
        } catch (Exception e) {
            QPS_CACHE.remove(target.getId());
            return ProbeResult.down(SimpleHttp.abbreviate(ServiceProbe.rootMessage(e)));
        }
    }

    private long queryNumeric(Connection conn, String sql) throws Exception {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return Long.parseLong(rs.getString(2));
            }
            return 0;
        }
    }
}
