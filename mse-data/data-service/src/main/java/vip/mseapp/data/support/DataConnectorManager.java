package vip.mseapp.data.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.mseapp.core.schedule.MseScheduled;
import org.springframework.stereotype.Component;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.data.config.DataCrypt;
import vip.mseapp.data.config.DataProperties;
import vip.mseapp.data.entity.DatDatasource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 注册数据源连接管理器：每个（租户, 数据源）一个 Hikari 池，闲置回收、变更重建。
 *
 * <p>独立于基座 dynamic-datasource（那是租户库路由，这里是租户注册的外部数据接入源）。
 * 借出的连接由调用方负责 close（归还池）；测试连接走 DriverManager 短连接不入池。
 * 驱动类只引用类名字符串，具体驱动由 data-boot 以 runtime 依赖打入。
 */
@Component
public class DataConnectorManager {

    private static final Logger log = LoggerFactory.getLogger(DataConnectorManager.class);

    /** 连接建立超时（毫秒），外部库不可达时快速失败 */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final DataProperties properties;
    private final DataCrypt crypt;

    /** key = tenantId:datasourceId */
    private final Map<String, PoolEntry> pools = new ConcurrentHashMap<>();

    public DataConnectorManager(DataProperties properties, DataCrypt crypt) {
        this.properties = properties;
        this.crypt = crypt;
    }

    /** 借出一个连接（调用方 close 归还池） */
    public Connection borrow(DatDatasource ds) {
        String key = keyOf(ds);
        PoolEntry entry = pools.compute(key, (k, old) -> {
            if (old != null && old.dataSource().isClosed()) {
                return null;
            }
            if (old == null) {
                return new PoolEntry(buildPool(ds), System.currentTimeMillis());
            }
            return new PoolEntry(old.dataSource(), System.currentTimeMillis());
        });
        try {
            return entry.dataSource().getConnection();
        } catch (SQLException e) {
            // 坏池就地销毁，下次重建，避免持续失败
            evict(key);
            throw new ServiceException(500, "获取数据源连接失败：" + e.getMessage());
        }
    }

    /** 连通性测试：短连接不入池，返回 null 表示成功，否则返回错误信息 */
    public String testConnection(DatDatasource ds) {
        try (Connection ignored = openDirect(ds)) {
            return null;
        } catch (SQLException e) {
            return e.getMessage();
        }
    }

    /** 数据源配置变更/删除后销毁旧池 */
    public void evict(Long tenantId, Long datasourceId) {
        evict(tenantId + ":" + datasourceId);
    }

    private void evict(String key) {
        PoolEntry entry = pools.remove(key);
        if (entry != null) {
            entry.dataSource().close();
        }
    }

    /** 每分钟回收闲置池 */
    @MseScheduled(taskId = "data-connector-sweep", name = "数据源连接池回收",
            description = "回收闲置的外部数据源 Hikari 连接池（纯内存，不访问数据库）",
            fixedDelay = 60_000, initialDelay = 120_000)
    public void sweepIdlePools() {
        long idleMs = properties.getPool().getIdleTimeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();
        pools.forEach((key, entry) -> {
            if (now - entry.lastAccess() > idleMs) {
                log.info("回收闲置数据源连接池：{}", key);
                evict(key);
            }
        });
    }

    private HikariDataSource buildPool(DatDatasource ds) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl(ds));
        config.setUsername(ds.getUsername());
        config.setPassword(crypt.decrypt(ds.getPassword()));
        config.setDriverClassName(driverClass(ds.getType()));
        config.setMaximumPoolSize(properties.getPool().getMaxPoolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(CONNECT_TIMEOUT_MS);
        config.setPoolName("mse-data-ds-" + ds.getTenantId() + "-" + ds.getId());
        return new HikariDataSource(config);
    }

    /** 短连接（测试/轻量采集），不入池；口令统一在此解密（调用方拿到的是密文实体） */
    public Connection openDirect(DatDatasource ds) throws SQLException {
        DriverManager.setLoginTimeout(CONNECT_TIMEOUT_MS / 1000);
        return DriverManager.getConnection(jdbcUrl(ds), ds.getUsername(), crypt.decrypt(ds.getPassword()));
    }

    /** 按类型拼 JDBC URL（默认参数与基座 docker 部署保持同风格；用户追加参数在后，同名覆盖） */
    public String jdbcUrl(DatDatasource ds) {
        String extra = ds.getExtraParams() == null || ds.getExtraParams().isBlank()
                ? "" : "&" + ds.getExtraParams().trim();
        return switch (ds.getType()) {
            case DatDatasource.TYPE_MYSQL -> ("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8"
                    + "&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                    + "&useInformationSchema=true%s").formatted(ds.getHost(), ds.getPort(), ds.getDbName(), extra);
            case DatDatasource.TYPE_DM -> "jdbc:dm://%s:%d?schema=%s&characterEncoding=UTF-8%s".formatted(
                    ds.getHost(), ds.getPort(), ds.getDbName(), extra);
            case DatDatasource.TYPE_KINGBASE -> "jdbc:kingbase8://%s:%d/%s?currentSchema=%s%s".formatted(
                    ds.getHost(), ds.getPort(), ds.getDbName(), ds.getDbName(),
                    extra);
            case DatDatasource.TYPE_POSTGRESQL -> "jdbc:postgresql://%s:%d/%s?currentSchema=public%s".formatted(
                    ds.getHost(), ds.getPort(), ds.getDbName(), extra);
            default -> throw new ServiceException(400, "不支持的数据源类型：" + ds.getType());
        };
    }

    public String driverClass(String type) {
        return switch (type) {
            case DatDatasource.TYPE_MYSQL -> "com.mysql.cj.jdbc.Driver";
            case DatDatasource.TYPE_DM -> "dm.jdbc.driver.DmDriver";
            case DatDatasource.TYPE_KINGBASE -> "com.kingbase8.Driver";
            case DatDatasource.TYPE_POSTGRESQL -> "org.postgresql.Driver";
            default -> throw new ServiceException(400, "不支持的数据源类型：" + type);
        };
    }

    private String keyOf(DatDatasource ds) {
        return ds.getTenantId() + ":" + ds.getId();
    }

    private record PoolEntry(HikariDataSource dataSource, long lastAccess) {
    }
}
