package vip.mseapp.search.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import vip.mseapp.core.concurrent.MseExecutors;

/**
 * 搜索引擎引擎配置：调度开关与扫描执行线程池。
 *
 * <p>线程池内任务由调度线程（无租户上下文）提交，服务层内部已用
 * {@code TenantContext.runIgnore/runAs} 显式声明租户语义。
 */
@Configuration
public class SearchEngineConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService searchExecutor() {
        // 经 MseExecutors 工厂：有界队列 + wrap 池内恢复提交线程租户（服务层 runAs/runIgnore 保留双保险）
        return MseExecutors.fixedPool("mse-search-worker", 4, 256);
    }
}
