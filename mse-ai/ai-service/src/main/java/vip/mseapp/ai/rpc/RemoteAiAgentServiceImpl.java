package vip.mseapp.ai.rpc;

import vip.mseapp.ai.api.RemoteAiAgentService;
import vip.mseapp.ai.api.dto.AiAgentOption;
import vip.mseapp.ai.entity.AiAgent;
import vip.mseapp.ai.service.AiAgentService;
import vip.mseapp.core.annotation.RpcService;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;

import java.util.List;

/**
 * 智能体选项列表契约实现：业务服务管理页（如 mse-oa「AI 智能体设置」）映射下拉数据源。
 *
 * <p>租户上下文由 RPC Filter 透传写入；按会话世界单边返回本世界已发布智能体
 * （口径与 {@link AiAgentService#listVisible(Long)} 一致）。无租户上下文（如单体未开租户）
 * 按平台世界处理。
 */
@RpcService(RemoteAiAgentService.class)
public class RemoteAiAgentServiceImpl implements RemoteAiAgentService {

    private final AiAgentService agentService;

    public RemoteAiAgentServiceImpl(AiAgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public List<AiAgentOption> listOptions(String keyword) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            tenantId = MseConstants.PLATFORM_TENANT_ID;
        }
        List<AiAgent> agents = agentService.listVisible(tenantId);
        return agents.stream()
                .filter(a -> matches(a, keyword))
                .map(this::toOption)
                .toList();
    }

    /** 关键字过滤：空关键字全量返回；非空匹配名称或编码（忽略大小写） */
    private boolean matches(AiAgent agent, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String lower = keyword.trim().toLowerCase();
        return contains(agent.getName(), lower) || contains(agent.getAgentCode(), lower);
    }

    private boolean contains(String text, String lowerKeyword) {
        return text != null && text.toLowerCase().contains(lowerKeyword);
    }

    private AiAgentOption toOption(AiAgent agent) {
        AiAgentOption option = new AiAgentOption();
        option.setAgentCode(agent.getAgentCode());
        option.setName(agent.getName());
        option.setDescription(agent.getDescription());
        option.setScope(agent.getScope());
        option.setAvatar(agent.getAvatar());
        return option;
    }
}
