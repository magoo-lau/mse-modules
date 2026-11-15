package vip.mseapp.ai.api;

import java.util.List;

import vip.mseapp.ai.api.dto.AiAgentDescriptor;

/**
 * 智能体登记契约（mse-ai 实现，业务积木消费）：把业务声明的智能体自动登记到智能体管理。
 *
 * <p>业务侧由 ai-api 的自动装配自动推送（见 support 包）——容器内实现 {@link AiAgentProvider}
 * 即可，微服务（dubbo）模式启动后自动登记，无需手工在智能体管理维护；单体聚合形态
 * mse-ai 直接收集容器内本地 Bean，本契约不会被触达。注册为幂等 upsert：按 agentCode
 * 新增或更新提供方权威字段（name/description/systemPrompt/toolCodes），模型/温度/
 * 开场白/启停状态/备注不被覆盖；agentCode 已被其他提供方（rpc_group 不同）占用时跳过，
 * 避免互相覆盖。
 */
public interface RemoteAiAgentRegistry {

    /**
     * 批量登记智能体描述。
     *
     * @param providerGroup 提供方服务名（spring.application.name，登记为 rpc_group 供溯源）
     * @param descriptors   描述清单（逐条独立处理：冲突/不合法条目跳过并告警，不拖垮整批）
     */
    void register(String providerGroup, List<AiAgentDescriptor> descriptors);
}
