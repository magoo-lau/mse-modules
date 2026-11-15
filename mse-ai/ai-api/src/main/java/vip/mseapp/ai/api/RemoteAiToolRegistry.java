package vip.mseapp.ai.api;

import java.util.List;

import vip.mseapp.ai.api.dto.AiToolDescriptor;

/**
 * AI 工具登记契约（mse-ai 实现，业务积木消费）：把业务工具描述自动登记到工具中心。
 *
 * <p>业务侧由 ai-api 的自动装配自动推送（见 support 包）——容器内实现 {@link RemoteAiTool}
 * 即可，微服务（dubbo）模式启动后自动登记，无需手工在工具中心维护；单体聚合形态
 * mse-ai 直接收集容器内本地 Bean，本契约不会被触达。注册为幂等 upsert：按 toolCode
 * 新增或更新描述字段（name/description/parametersJsonSchema），管理端启停状态（status）
 * 不被覆盖；toolCode 与其他提供方（rpc_group 不同）冲突时跳过，避免互相覆盖。
 */
public interface RemoteAiToolRegistry {

    /**
     * 批量登记工具描述。
     *
     * @param providerGroup 提供方服务名（spring.application.name，登记为 rpc_group 供泛化调用定向）
     * @param descriptors   描述清单（逐条独立处理：冲突/不合法条目跳过并告警，不拖垮整批）
     */
    void register(String providerGroup, List<AiToolDescriptor> descriptors);
}
