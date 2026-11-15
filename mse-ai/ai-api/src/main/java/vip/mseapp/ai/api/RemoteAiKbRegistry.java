package vip.mseapp.ai.api;

import java.util.List;

import vip.mseapp.ai.api.dto.AiKbDescriptor;

/**
 * 知识库登记契约（mse-ai 实现，业务积木消费）：把业务声明的知识库编码目录登记到平台。
 *
 * <p>业务侧由 ai-api 的自动装配自动推送（见 support 包）——容器内实现 {@link AiKbProvider}
 * 即可，微服务（dubbo）模式启动后自动登记，无需手工维护；单体聚合形态 mse-ai 直接收集
 * 容器内本地 Bean，本契约不会被触达。注册为幂等 upsert：按 code 新增或更新提供方权威
 * 字段（name/description）；code 已被其他提供方（rpc_group 不同）占用时跳过，避免互相覆盖。
 */
public interface RemoteAiKbRegistry {

    /**
     * 批量登记知识库声明。
     *
     * @param providerGroup 提供方服务名（spring.application.name，登记为 rpc_group 供溯源）
     * @param descriptors   描述清单（逐条独立处理：冲突/不合法条目跳过并告警，不拖垮整批）
     */
    void register(String providerGroup, List<AiKbDescriptor> descriptors);
}
