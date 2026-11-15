package vip.mseapp.ai.api;

import java.util.List;

import vip.mseapp.ai.api.dto.AiBizModuleDescriptor;

/**
 * 业务模块声明提供方（业务积木实现，@Component 注册）：把「业务模块」自声明给 mse-ai，
 * 供通用业务分析入口（biz_query）做问题域路由——平台侧不连业务库，查询一律转发到
 * {@code toolCode} 指向的模块查询工具（通常为 {@link AbstractBizQueryTool} 子类），
 * 在模块自身进程内执行。
 *
 * <p>单体聚合形态 mse-ai 启动扫描本地 Bean 自动登记；微服务（dubbo）模式经
 * ai-api 推送器周期重推至 mse-ai 模块注册中心（幂等 upsert，提供方权威）。
 */
public interface AiBizModuleProvider {

    /** 声明的业务模块清单（通常一个模块工程一条） */
    List<AiBizModuleDescriptor> modules();
}
