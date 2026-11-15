package vip.mseapp.ai.api;

import vip.mseapp.ai.api.dto.AiAgentOption;

import java.util.List;

/**
 * 智能体选项列表契约（ai-service 实现）。
 *
 * <p>供业务服务管理页做「场景 → 智能体」映射的下拉数据源（如 mse-oa「AI 智能体设置」）：
 * 返回当前租户可见的已发布智能体（平台公共 + 本租户自有）。租户上下文经 RPC 隐式透传，
 * 契约方法无需携带 tenantId。
 */
public interface RemoteAiAgentService {

    /**
     * 列出当前租户可见的已发布智能体选项。
     *
     * @param keyword 关键字（可空，匹配名称或编码，忽略大小写）
     * @return 选项列表（agentCode 全局唯一）
     */
    List<AiAgentOption> listOptions(String keyword);
}
