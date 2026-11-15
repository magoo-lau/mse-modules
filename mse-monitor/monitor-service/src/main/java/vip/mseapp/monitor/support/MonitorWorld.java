package vip.mseapp.monitor.support;

import org.springframework.stereotype.Component;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.monitor.config.MonitorProperties;

import java.util.function.Supplier;

/**
 * 监控数据世界边界：按 {@code mse.monitor.offline} 标记决定 mon_* 读写的路由与过滤口径。
 *
 * <p>在线（缺省）：有租户上下文按上下文执行——平台会话经路由拦截器推平台库（tenant 0 行），
 * 租户会话行级过滤本租户库，监控由平台运营，租户端靠人工不授权收口；无上下文（调度线程等
 * 后台场景）默认平台语义 tenant 0 落平台库。
 *
 * <p>离线（租户私有部署自管）：统一 {@code runIgnore} 执行——免行级条件、免数据源路由，
 * mon_* 全部直连本地缺省库（本地离线库），平台/租户会话看到的是同一个本地监控世界
 * （本机 OS 目标 = 本地世界 tenant 0 行，同样可见）；采集/查询/告警/通知全走本地。
 */
@Component
public class MonitorWorld {

    private final MonitorProperties properties;

    public MonitorWorld(MonitorProperties properties) {
        this.properties = properties;
    }

    /** 离线模式判定 */
    public boolean offline() {
        return properties.isOffline();
    }

    /** 在线=有上下文按上下文执行、无上下文默认平台语义（runAsPlatform 落平台库）；离线=runIgnore 本地单一监控世界 */
    public <T> T scope(Supplier<T> action) {
        if (properties.isOffline()) {
            return TenantContext.runIgnore(action::get);
        }
        return TenantContext.getTenantId() != null
                ? action.get() : TenantContext.runAsPlatform(action::get);
    }

    /** 无返回值版 {@link #scope(Supplier)} */
    public void scope(Runnable action) {
        if (properties.isOffline()) {
            TenantContext.runIgnore(action::run);
        } else if (TenantContext.getTenantId() != null) {
            action.run();
        } else {
            TenantContext.runAsPlatform(action::run);
        }
    }
}
