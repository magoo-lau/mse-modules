package vip.mseapp.data.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.data.entity.DatDatasource;
import vip.mseapp.mybatis.datasource.TenantRawConnectionRouter;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 平台主库防呆护栏：禁止把中台库自身（master 数据源）登记/用作「外部数据源」。
 *
 * <p>外部数据源模式按「数据源归属租户」做数据源级隔离，不注入租户行级条件；
 * 若登记的其实是平台主库（共享库形态，全租户数据同库），自定义 SQL 即可跨租户取数。
 * 这里以主库 JDBC 坐标（host/port/dbName）比对做防呆拦截，登记与执行两侧共用；
 * 属提示性防线而非安全边界（真正的边界是主库凭据不外发），坐标解析失败时放行不阻断。
 */
@Component
public class MasterDbGuard {

    private static final Logger log = LoggerFactory.getLogger(MasterDbGuard.class);

    private final TenantRawConnectionRouter masterConnections;

    /** 主库坐标缓存；FAILED = 解析失败（此后恒放行） */
    private volatile Coordinates coordinates;

    public MasterDbGuard(TenantRawConnectionRouter masterConnections) {
        this.masterConnections = masterConnections;
    }

    /** 命中主库坐标时抛 400（登记与执行两侧共用同一话术） */
    public void assertNotMaster(DatDatasource ds) {
        Coordinates master = resolve();
        if (master != null && master.matches(ds.getHost(), ds.getPort(), ds.getDbName())) {
            throw new ServiceException(400,
                    "不允许将平台主库登记为外部数据源：请改用内置「中台库」来源（自动按租户隔离）");
        }
    }

    /** 懒解析主库 JDBC URL（首次取连接读 metadata，之后缓存） */
    private Coordinates resolve() {
        Coordinates cached = coordinates;
        if (cached != null) {
            return cached == Coordinates.FAILED ? null : cached;
        }
        synchronized (this) {
            if (coordinates != null) {
                return coordinates == Coordinates.FAILED ? null : coordinates;
            }
            try (Connection conn = masterConnections.open(MasterDbGuard.class)) {
                String url = conn.getMetaData().getURL();
                Coordinates parsed = Coordinates.parse(url);
                if (parsed != null) {
                    log.info("主库防呆护栏已就绪：{}:{}/{}", parsed.host(), parsed.port(), parsed.dbName());
                    coordinates = parsed;
                } else {
                    log.warn("主库 JDBC URL 无法解析坐标，防呆护栏放行：{}", url);
                    coordinates = Coordinates.FAILED;
                }
                return parsed;
            } catch (SQLException e) {
                log.warn("主库坐标解析失败，防呆护栏本次放行：{}", e.getMessage());
                coordinates = Coordinates.FAILED;
                return null;
            }
        }
    }

    /**
     * 主库坐标：对 jdbc:xxx://host[:port][/db] 形态容错解析（mysql/dm/kingbase8/postgresql 通用；
     * dm 形态可能无 path，dbName 为 null，此时仅按 host+port 比对）。
     */
    private record Coordinates(String host, Integer port, String dbName) {

        static final Coordinates FAILED = new Coordinates("", null, null);

        static Coordinates parse(String jdbcUrl) {
            if (jdbcUrl == null || !jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
                return null;
            }
            int schemeEnd = jdbcUrl.indexOf("://");
            if (schemeEnd <= 0) {
                return null;
            }
            String rest = jdbcUrl.substring(schemeEnd + 3);
            int query = rest.indexOf('?');
            if (query >= 0) {
                rest = rest.substring(0, query);
            }
            int slash = rest.indexOf('/');
            String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
            String db = slash >= 0 ? rest.substring(slash + 1) : null;
            String host;
            Integer port = null;
            int colon = hostPort.indexOf(':');
            if (colon >= 0) {
                host = hostPort.substring(0, colon);
                try {
                    port = Integer.valueOf(hostPort.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    // 非纯端口片段（如 IPv6 尾段），保持无端口
                }
            } else {
                host = hostPort;
            }
            if (host.isBlank()) {
                return null;
            }
            return new Coordinates(normalizeHost(host), port, db);
        }

        boolean matches(String host, Integer port, String dbName) {
            if (!normalizeHost(host).equalsIgnoreCase(this.host)) {
                return false;
            }
            if (this.port != null && port != null && !this.port.equals(port)) {
                return false;
            }
            // 主库库名未知（dm 形态）时仅按 host+port 判定；已知时必须同库才算命中（同机其他库是合法外部源）
            return this.dbName == null || this.dbName.equalsIgnoreCase(dbName);
        }

        private static String normalizeHost(String host) {
            return host == null ? "" : ("localhost".equalsIgnoreCase(host.trim()) ? "127.0.0.1" : host.trim());
        }
    }
}
