package vip.mseapp.ai.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.entity.AiWorkflowRun;
import vip.mseapp.ai.mapper.AiWorkflowRunMapper;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.workflow.model.NodeTrace;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.context.TenantContext;

/**
 * 工作流运行历史记录：运行收口时落 {@link AiWorkflowRun}（轨迹快照随行）。
 *
 * <p>记录失败只 warn 不影响运行链路；插入以 FlowRunEnv 显式 tenantId 经 {@link TenantContext#runAs}
 * 随世界路由（编排线程池无请求上下文）。截断约定：query 500 字符、answer 2000 字符、error 500 字符、
 * 轨迹 JSON 60_000 字符（正常远小于该值，防异常节点输出撑爆）。
 */
@Component
public class WorkflowRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunRecorder.class);

    private static final int MAX_QUERY = 500;
    private static final int MAX_ANSWER = 2000;
    private static final int MAX_ERROR = 500;
    private static final int MAX_TRACES_JSON = 60_000;

    private final AiWorkflowRunMapper runMapper;

    public WorkflowRunRecorder(AiWorkflowRunMapper runMapper) {
        this.runMapper = runMapper;
    }

    /** 运行收口记录（成功/失败/取消共用；ctx 可为 null——上下文未就绪即失败的场景） */
    public void record(FlowRunEnv env, WorkflowContext ctx, String query,
                       String status, String error, long elapsedMs) {
        try {
            AiWorkflowRun run = new AiWorkflowRun();
            run.setTenantId(env.tenantId() == null ? 0L : env.tenantId());
            run.setAgentCode(env.agentCode());
            run.setAgentName(env.agentName());
            run.setChannel(env.channel());
            run.setSessionId(env.sessionId());
            run.setStatus(status);
            run.setQueryText(truncate(query, MAX_QUERY));
            if (ctx != null) {
                String answer = ctx.getFinalAnswer();
                if (answer == null || answer.isBlank()) {
                    Object output = ctx.getFinalOutput();
                    answer = output == null ? null : vip.mseapp.ai.workflow.model.VarRenderer.display(output);
                }
                run.setAnswer(truncate(answer, MAX_ANSWER));
                run.setNodeTotal(ctx.traces().size());
                run.setTracesJson(truncate(AiJson.toJson(ctx.traces()), MAX_TRACES_JSON));
            }
            run.setError(truncate(error, MAX_ERROR));
            run.setElapsedMs(elapsedMs);
            TenantContext.runAs(run.getTenantId(), () -> runMapper.insert(run));
        } catch (Exception e) {
            log.warn("[mse-ai] 运行历史记录失败: agent={}, channel={}", env.agentCode(), env.channel(), e);
        }
    }

    /** 运行历史分页（租户侧：插件自动隔离；平台侧：runIgnore 跨租户，按 agentCode 过滤） */
    public Page<AiWorkflowRun> page(long pageNum, long pageSize, String agentCode, String status,
                                    String channel, boolean crossTenant) {
        LambdaQueryWrapper<AiWorkflowRun> wrapper = new LambdaQueryWrapper<AiWorkflowRun>()
                .eq(agentCode != null && !agentCode.isBlank(), AiWorkflowRun::getAgentCode, agentCode)
                .eq(status != null && !status.isBlank(), AiWorkflowRun::getStatus, status)
                .eq(channel != null && !channel.isBlank(), AiWorkflowRun::getChannel, channel)
                .orderByDesc(AiWorkflowRun::getId);
        if (crossTenant) {
            return TenantContext.runIgnore(() -> runMapper.selectPage(new Page<>(pageNum, pageSize), wrapper));
        }
        return runMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /** 运行详情（含轨迹快照；跨租户访问由调用方校验归属） */
    public AiWorkflowRun detail(Long id, boolean crossTenant) {
        AiWorkflowRun run = crossTenant
                ? TenantContext.runIgnore(() -> runMapper.selectById(id))
                : runMapper.selectById(id);
        if (run == null) {
            throw new vip.mseapp.core.exception.ServiceException(404, "运行记录不存在");
        }
        return run;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }
}
