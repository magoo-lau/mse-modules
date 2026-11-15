package vip.mseapp.monitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import vip.mseapp.core.concurrent.MseExecutors;

/**
 * 监控引擎配置：采集执行线程池与调度开关（内存态调度，多实例部署需外置调度）。
 *
 * <p>线程池任务不预置租户上下文：采集链路（CollectService）按目标归属租户
 * runAs 路由落库，调度器跨数据源扫描。
 */
@Configuration
public class MonitorEngineConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService collectExecutor(MonitorProperties properties) {
        // 刻意无租户（NoContext）：调度器跨数据源扫描、任务按目标归属租户 runAs（见类注释）——
        // wrap 反而会把提交线程的租户泄漏进采集任务；有界队列防堆积
        return MseExecutors.fixedPoolNoContext("mse-monitor-collect",
                Math.max(1, properties.getExecutorParallelism()), 256);
    }
}
