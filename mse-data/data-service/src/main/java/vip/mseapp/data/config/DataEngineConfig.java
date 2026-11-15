package vip.mseapp.data.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vip.mseapp.core.concurrent.TenantTaskGates;

import java.util.concurrent.ExecutorService;
import vip.mseapp.core.concurrent.MseExecutors;

/**
 * 数据中台引擎配置：同步执行线程池与调度开关。
 *
 * <p>线程池内的任务用 TenantContext.runAs 显式声明租户语义，无需 TTL 包装；
 * 租户并发闸门（同步/质量共用一个执行域）防单租户占满线程池。
 */
@Configuration
public class DataEngineConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService syncExecutor(DataProperties properties) {
        // 经 MseExecutors 工厂：有界队列 + wrap 池内恢复提交线程租户（任务内显式 runAs 保留双保险），
        // 原 newFixedThreadPool 无界队列在推送风暴下有堆积风险
        return MseExecutors.fixedPool("mse-data-sync",
                Math.max(1, properties.getSync().getExecutorParallelism()), 256);
    }

    @Bean
    public TenantTaskGates dataTenantTaskGates(DataProperties properties) {
        // key=data：「调度器」设置页热更通道按此键匹配（gates.data=每租户上限）
        return new TenantTaskGates("data", properties.getSync().getTenantConcurrency());
    }
}
