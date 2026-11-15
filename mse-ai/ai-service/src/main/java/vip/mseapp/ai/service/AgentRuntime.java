package vip.mseapp.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import vip.mseapp.ai.entity.AiAgent;
import vip.mseapp.ai.kb.KbRagAdvisor;
import vip.mseapp.ai.kb.KbRetrievalService;
import vip.mseapp.ai.kb.vector.KbVectorClient;

import java.util.Arrays;
import java.util.List;

/**
 * 智能体运行时：把智能体配置组装为 Spring AI {@link ChatClient}
 * （系统提示词 + 落库记忆 + 工具回调 + RAG 检索增强，按需挂载）。
 *
 * <p>每次对话构建轻量 ChatClient（模型实例经 AiModelFactory 缓存）；
 * 工具上下文以显式 ToolInvocationContext 闭包携带，不依赖 ThreadLocal。
 * RAG 经 {@link KbRagAdvisor} 走统一检索管线（混合召回 + rerank），检索范围
 * 过滤 tenantId + kbIds（租户收口），检索参数取绑定知识库配置（topK 取最大、
 * 阈值取最小）。
 */
@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    /**
     * 直出图表输出纪律（chart_direct=1 时追加在系统提示词末尾）：一句话极简结论 + 图表中文列，
     * 压制智能体自有提示词里「Markdown 表格解读/口径说明」类输出指令——查询结果由前端数据
     * 卡片独立渲染（工具结果含 columns+rows 即出图，与文字解耦），无需模型复述。
     */
    private static final String CHART_DIRECT_PROMPT = """
            【输出纪律（最高优先级）】凡查询类工具执行成功，最终回答只允许输出一句话极简结论（不超过 25 字，如「共 1,219 条审批记录」），严禁输出查询思路、Markdown 表格、要点罗列、口径与数据说明等任何其他文字——查询结果已由系统自动渲染为图表卡片展示给用户。工具执行失败或未查到数据时不受此限，用简短文字如实说明。
            【图表中文规范】生成查询 SQL 时：所有输出列必须带中文别名（如 COUNT(*) AS 记录数量）；分类/状态等枚举维度必须用 CASE WHEN 把编码值映射为中文标签（如 CASE status WHEN 'APPROVED' THEN '已审批' WHEN 'REJECTED' THEN '已拒绝' END，按实际业务语义翻译），确保图表坐标轴、图例与表格表头全部为中文。""";

    private final AiModelFactory modelFactory;
    private final DbChatMemory chatMemory;
    private final AiToolService toolService;
    private final ObjectProvider<KbVectorClient> vectorClient;
    private final KbRetrievalService retrievalService;

    public AgentRuntime(AiModelFactory modelFactory, DbChatMemory chatMemory,
                        AiToolService toolService, ObjectProvider<KbVectorClient> vectorClient,
                        KbRetrievalService retrievalService) {
        this.modelFactory = modelFactory;
        this.chatMemory = chatMemory;
        this.toolService = toolService;
        this.vectorClient = vectorClient;
        this.retrievalService = retrievalService;
    }

    /**
     * 组装对话客户端。
     *
     * @param agent       智能体配置
     * @param toolContext 工具审计上下文（agentCode/sessionId/tenantId）
     * @param withMemory  是否挂载多轮记忆（RPC 无状态调用传 false）
     */
    public ChatClient buildClient(AiAgent agent, AiToolService.ToolInvocationContext toolContext,
                                  boolean withMemory) {
        return buildClient(agent, toolContext, withMemory, null);
    }

    /**
     * 组装对话客户端（带工具执行观察者：对话链路实时接收工具事件与「模型未调用工具」判定依据）。
     */
    public ChatClient buildClient(AiAgent agent, AiToolService.ToolInvocationContext toolContext,
                                  boolean withMemory, ToolExecutionSupport.ToolExecutionListener toolListener) {
        // 按会话世界解析模型配置（模型行世界自有，agent.modelId 为世界内绑定）
        ChatModel chatModel = modelFactory.getChatModel(agent.getModelId());
        ChatClient.Builder builder = ChatClient.builder(chatModel);

        String systemPrompt = resolveSystemPrompt(agent);
        if (!systemPrompt.isBlank()) {
            builder.defaultSystem(systemPrompt);
        }
        if (withMemory) {
            // 记忆读写经每请求租户绑定包装（runAs 显式锚定）：MessageChatMemoryAdvisor 在链内
            // 调度线程回调，Reactor 传播对部分链路形态不生效——闭包租户是确定性兜底
            builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(
                    new TenantBoundChatMemory(toolContext.tenantId(), chatMemory)).build());
        }

        List<ToolCallback> tools = toolService.resolveCallbacks(splitCsv(agent.getToolCodes()), toolContext,
                toolListener);
        if (!tools.isEmpty()) {
            builder.defaultToolCallbacks(tools);
            if (toolListener instanceof ToolExecutionSupport.ToolExecutionRecorder recorder) {
                recorder.markResolved(tools.size());
            }
            log.debug("[mse-ai] 智能体 {} 挂载工具: {}", agent.getAgentCode(), agent.getToolCodes());
        }

        List<Long> kbIds = splitCsv(agent.getKbIds()).stream().map(Long::valueOf).toList();
        if (!kbIds.isEmpty()) {
            KbVectorClient client = vectorClient.getIfAvailable();
            if (client == null) {
                log.warn("[mse-ai] 智能体 {} 绑定了知识库但向量库未部署，RAG 跳过",
                        agent.getAgentCode());
            } else {
                KbRetrievalService.RetrievalParams params =
                        retrievalService.resolveParams(toolContext.tenantId(), kbIds);
                builder.defaultAdvisors(new KbRagAdvisor(retrievalService, toolContext.tenantId(),
                        kbIds, params.topK(), params.threshold(), 100));
                log.debug("[mse-ai] 智能体 {} 挂载 RAG: kbIds={}, topK={}, threshold={}",
                        agent.getAgentCode(), kbIds, params.topK(), params.threshold());
            }
        }

        if (agent.getTemperature() != null) {
            builder.defaultOptions(ChatOptions.builder().temperature(agent.getTemperature()));
        }
        return builder.build();
    }

    /**
     * 系统提示词组装（对话链路与调试回显共用口径）：智能体自有提示词 +
     * 直出图表输出纪律追加（chart_direct=1，末位 + 最高优先级标注以覆盖自有指令）。
     */
    public static String resolveSystemPrompt(AiAgent agent) {
        String prompt = agent.getSystemPrompt();
        if (!Integer.valueOf(1).equals(agent.getChartDirect())) {
            return prompt == null ? "" : prompt;
        }
        String base = prompt == null || prompt.isBlank() ? "" : prompt + "\n\n";
        return base + CHART_DIRECT_PROMPT;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
