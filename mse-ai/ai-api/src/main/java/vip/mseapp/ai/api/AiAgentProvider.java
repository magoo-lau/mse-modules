package vip.mseapp.ai.api;

import java.util.List;

import vip.mseapp.ai.api.dto.AiAgentDescriptor;

/**
 * 智能体声明契约（业务积木实现）：容器内 {@code @Component} 声明即可，mse-ai 自动把
 * 描述登记进智能体管理（单体聚合形态启动扫描直收；微服务 dubbo 模式经推送器周期重推）。
 *
 * <p>登记为幂等 upsert 平台行（tenant_id=0，默认已发布），各租户首次使用时懒补齐可编辑
 * 副本；提供方权威字段为 name/description/systemPrompt/toolCodes，模型/温度/开场白/
 * 启停/备注由管理侧维护不被覆盖。运行期对话仍走 {@link RemoteAiChatService}（按
 * agentCode 取本世界智能体），本契约不承载调用。
 */
public interface AiAgentProvider {

    /**
     * 声明本模块提供的智能体清单。
     *
     * @return 描述清单（agentCode 必填；单条不合法仅跳过该条，不影响整批）
     */
    List<AiAgentDescriptor> agents();
}
