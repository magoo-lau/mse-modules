package vip.mseapp.monitor.engine.probe;

import vip.mseapp.monitor.entity.MonTarget;

/**
 * 监控探测器 SPI：按 target_type 分发，实现类声明为 Spring Bean 即被采集引擎发现。
 */
public interface Probe {

    /** 是否支持该目标类型 */
    boolean supports(String targetType);

    /**
     * 执行一次探测（同步，实现方自行保证在 timeoutMs 内返回）。
     *
     * @param target    监控目标
     * @param timeoutMs 超时（毫秒）
     */
    ProbeResult probe(MonTarget target, int timeoutMs);
}
