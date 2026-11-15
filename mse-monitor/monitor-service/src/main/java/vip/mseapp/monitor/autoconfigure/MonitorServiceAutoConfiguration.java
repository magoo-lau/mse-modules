package vip.mseapp.monitor.autoconfigure;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import vip.mseapp.mybatis.config.MseMybatisAutoConfiguration;
import vip.mseapp.core.annotation.TenantServiceModule;

/**
 * 监控中心业务服务自动装配（Spring Boot SPI）。
 *
 * <p>接入方仅需引入 monitor-service 依赖：目标管理 / 采集引擎（探测器 + 调度）/
 * 告警通知（状态机 + 站内信/Webhook）/ Mapper 均由此注册，无需 component-scan 业务包名，
 * 也无需 @MapperScan。控制器由 {@link MonitorControllerAutoConfiguration} 装配，
 * 两者均经 {@code META-INF/spring/...AutoConfiguration.imports} 登记。
 *
 * <p>周期任务（采集扫描/过期清理）经统一调度中心 @MseScheduled 声明（延迟启动 +
 * 多实例分布式锁防重 + 平台端「任务调度」可管理）。
 */
@AutoConfiguration(before = MseMybatisAutoConfiguration.class)
@MapperScan(basePackages = "vip.mseapp.monitor.mapper", annotationClass = Mapper.class)
@ComponentScan(basePackages = {"vip.mseapp.monitor.config", "vip.mseapp.monitor.engine",
        "vip.mseapp.monitor.service", "vip.mseapp.monitor.support"})
@TenantServiceModule("mse-monitor")
public class MonitorServiceAutoConfiguration {
}
