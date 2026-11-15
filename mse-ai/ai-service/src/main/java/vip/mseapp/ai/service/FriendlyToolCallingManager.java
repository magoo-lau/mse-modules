package vip.mseapp.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.support.ToolTracePropagation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具调用上限文案的友好化装饰器：Spring AI 的 {@code DefaultToolCallingManager} 在
 * {@code RETURN_ERROR_RESPONSE} 行为下把硬编码英文文案（"Tool call limit (N) exceeded
 * for tool 'x'..."）作为工具结果写回对话历史，模型常把它原样复述进回答——终端用户看到
 * 一段纯技术性的英文报错。本装饰器在结果返回给模型前把这类文案改写为中文收尾指令：
 * 告知本轮额度已用完、停止再调用工具、基于已获取的信息直接作答——模型照做即自然收尾，
 * 即便仍被复述，用户看到的也是可读的中文而非技术报错。
 *
 * <p>改写同时经 {@link ToolTracePropagation} 取当前对话的 recorder 补录超限轨迹
 * （SSE phase=limit 事件 + 消息 ext 步骤）并告警日志——超限拦截不经过 ToolCallback
 * 包装层，常规监听看不见，这里是唯一感知点。
 *
 * <p>本类同时是<b>模型轮次边界的唯一观测点</b>：Spring AI 内部工具循环对上层只暴露
 * 一次模型调用，每轮边界发生在 {@code executeToolCalls} 里——入口 {@code prompt} 即
 * 该轮模型实际收到的完整输入（系统提示词 + RAG 注入 + 记忆/历史 + 工具回传），出口
 * 历史即下一轮的输入。verbose 模式（仅调试通道）在此快照前后消息列表，经 recorder
 * 下发 phase=turn/turn-end 事件，调试台据此还原「每轮模型看到了什么」的全过程时间线。
 */
public final class FriendlyToolCallingManager implements ToolCallingManager {

    private static final Logger log = LoggerFactory.getLogger(FriendlyToolCallingManager.class);

    /** Spring AI ToolCallLimits 两段超限文案的固定前缀（单工具/合计；升级 Spring AI 时需复核） */
    private static final String PER_TOOL_LIMIT_PREFIX = "Tool call limit (";
    private static final String TOTAL_LIMIT_PREFIX = "Total tool call limit (";

    /** 超限文案里的上限数值（前缀后紧跟 "(N)"） */
    private static final Pattern LIMIT_NUMBER = Pattern.compile("^\\w[^(]*\\((\\d+)\\)");

    /** 改写后的工具结果：对模型是收尾指令，被复述时对用户也是可读中文 */
    private static final String FRIENDLY_LIMIT_TEXT =
            "本轮工具调用次数已达上限，系统已停止执行新的工具调用。请勿再尝试调用任何工具，"
                    + "直接基于以上已获取的信息回答用户的问题；若信息不足以完整回答，"
                    + "请说明已查到的部分，并建议用户换一种问法或缩小范围后再试。";

    /**
     * 空结果硬终止（整条替换；指令置顶最醒目，原始返回内嵌保留供作答引用 tip）。
     * 工具回调层（{@code AiToolService.executeAndLog}）与改写层共用——回调层包装后
     * 内容不再形如空数据 JSON，改写层判定自然跳过，不会双重叠加。
     */
    public static String hardNoDataText(String tool, String originalJson) {
        return "【系统指令】工具 " + tool + " 本次调用成功但未查询到任何数据（结果为空，或表不存在/无权限），"
                + "系统已终止工具循环：请勿再调用任何工具重试——包括更换 keyword、表名或查询条件。"
                + "请立即向用户如实说明未查询到相关数据，并可基于下方原始返回中的 tip 提示，建议用户调整问法或检查权限。"
                + "原始返回：" + originalJson;
    }

    /** 结果 JSON 中的数据数组字段名（任一存在且全空即视为「成功但无数据」；columns 等元数据数组不计） */
    private static final List<String> DATA_ARRAY_FIELDS =
            List.of("rows", "tables", "records", "data", "list", "results", "items");

    /** 轮次快照单条消息文本字符上限（调试台查看器内限高滚动，取值宁可偏大） */
    private static final int MAX_SNAPSHOT_CHARS = 4000;

    /** 轮次快照工具调用参数/响应字符上限 */
    private static final int MAX_SNAPSHOT_DETAIL_CHARS = 2000;

    /** 轮次快照消息条数上限（超长对话截断） */
    private static final int MAX_SNAPSHOT_MESSAGES = 60;

    private final ToolCallingManager delegate;

    public FriendlyToolCallingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    /**
     * 平台统一构建口径（{@code mse.ai.tool-max-calls-per-tool / tool-max-total-calls}）：
     * 模型中心建档模型（{@code AiModelFactory}）与 spring.ai.* 自动装配的兜底模型共用，
     * 避免兜底路径落回库默认（单工具 40/合计 150）+ 硬编码英文文案。
     */
    public static FriendlyToolCallingManager fromProperties(AiPlatformProperties properties) {
        return new FriendlyToolCallingManager(ToolCallingManager.builder()
                .maxCallsPerTool(properties.getToolMaxCallsPerTool())
                .maxTotalToolCalls(properties.getToolMaxTotalCalls())
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
                .build());
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        // 空结果关闭 → 异常通道终止 advisor 工具循环：ToolCallingAdvisor 仅有三个出口
        //（模型不再要工具 / 全部 returnDirect / 捕获 ToolCallLimitExceededException），
        // 关闭工具再次被请求时抛出本异常即机械终止整个循环——advisor 以 partialResult
        //（末条工具响应 = 上一轮终止指令文本）作为最终回答收尾，客户端看到完整中文说明。
        // 关闭由回调层（AiToolService 短路）在前几轮完成并留痕，此处只负责断路。
        ToolExecutionSupport.ToolExecutionRecorder recorder = ToolTracePropagation.current();
        if (recorder != null) {
            String closedTool = firstClosedRequestedTool(chatResponse, recorder);
            if (closedTool != null) {
                log.warn("[mse-ai] 工具 {} 空结果机械关闭后仍被请求，经异常通道终止工具循环", closedTool);
                throw new ToolCallLimitExceededException(closedTool,
                        ToolExecutionSupport.EMPTY_RESULT_CLOSE_THRESHOLD,
                        ToolExecutionResult.builder()
                                .conversationHistory(prompt.getInstructions())
                                .returnDirect(false)
                                .build());
            }
        }
        // 轮次边界登记（verbose=调试通道才生效）：入口 prompt = 该轮模型实际输入，
        // 出口历史 = 下一轮模型输入；先于 delegate 登记开始边界，其后的工具执行事件
        // （onStart/onComplete）才携带正确轮次号
        boolean verbose = recorder != null && recorder.isVerbose();
        if (verbose) {
            recorder.recordTurnBegin(snapshotMessages(prompt.getInstructions()));
        }
        ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);
        if (verbose) {
            recorder.recordTurnEnd(snapshotMessages(result.conversationHistory()));
        }
        List<Message> history = result.conversationHistory();
        // 空结果终止引导（先于超限改写：成功但无数据时阻止模型继续循环重试）
        history = rewriteEmptySuccess(history, recorder);
        if (history.stream().noneMatch(FriendlyToolCallingManager::carriesLimitNotice)) {
            return ToolExecutionResult.builder()
                    .conversationHistory(history)
                    .returnDirect(result.returnDirect())
                    .build();
        }
        recordLimitBreach(history);
        List<Message> rewritten = new ArrayList<>(history.size());
        for (Message message : history) {
            rewritten.add(message instanceof ToolResponseMessage toolMessage && carriesLimitNotice(toolMessage)
                    ? rewrite(toolMessage) : message);
        }
        return ToolExecutionResult.builder()
                .conversationHistory(rewritten)
                .returnDirect(result.returnDirect())
                .build();
    }

    /**
     * 空结果终止引导：工具「成功 + 数据数组全空」对模型没有终止语义（示例：
     * {@code {"total":0,"tables":[],"tip":...}}），且结果里的 tip（如"用 table 表名
     * 或 metaTableId 查看列清单"）反而会引导模型换方式继续调——直到上限拦截空转数十轮。
     * 与超限文案改写同一机制：空结果一律<b>首次即整条替换为硬终止指令</b>（指令置顶，
     * 原始返回内嵌保留供作答引用 tip），不给予"换方式再试一次"的机会。经
     * {@code ToolTracePropagation} 取 recorder 记 {@code phase=nodata} 轨迹事件
     * （无 recorder 时只改写不留痕）。
     */
    private static List<Message> rewriteEmptySuccess(List<Message> history,
                                                     ToolExecutionSupport.ToolExecutionRecorder recorder) {
        List<Message> rewritten = new ArrayList<>(history.size());
        boolean anyChanged = false;
        for (Message message : history) {
            if (message instanceof ToolResponseMessage toolMessage) {
                boolean messageChanged = false;
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                    String content = response.responseData();
                    if (isEmptyDataResult(content)) {
                        String tool = response.name() == null ? "unknown" : response.name();
                        boolean first = recorder == null || recorder.markNoDataDirected(tool);
                        responses.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(),
                                hardNoDataText(tool, content)));
                        messageChanged = true;
                        anyChanged = true;
                        log.warn("[mse-ai] 工具 {} 返回空数据（成功但无匹配），已硬终止工具循环阻止重试{}",
                                tool, first ? "" : "（重复空结果）");
                        if (recorder != null) {
                            recorder.recordNoData(tool, first);
                        }
                    } else {
                        responses.add(response);
                    }
                }
                if (messageChanged) {
                    rewritten.add(ToolResponseMessage.builder()
                            .responses(responses)
                            .metadata(toolMessage.getMetadata())
                            .build());
                    continue;
                }
            }
            rewritten.add(message);
        }
        return anyChanged ? rewritten : history;
    }

    /**
     * 「成功但无数据」判定：结果为 JSON 对象，且数据数组字段（rows/tables/records/
     * data/list/results/items）至少出现一个、出现的全部为空——columns 等元数据数组与
     * tip/message 等提示字段不参与判定（非 JSON / 文本型结果 / 有任一数据数组非空均不算）。
     */
    public static boolean isEmptyDataResult(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            JsonNode json = AiJson.tryParse(content);
            if (json == null || !json.isObject()) {
                return false;
            }
            boolean hasDataArray = false;
            for (String field : DATA_ARRAY_FIELDS) {
                JsonNode node = json.get(field);
                if (node == null || !node.isArray()) {
                    continue;
                }
                hasDataArray = true;
                if (!node.isEmpty()) {
                    return false;
                }
            }
            return hasDataArray;
        } catch (Exception e) {
            return false;
        }
    }

    /** 本轮请求的工具中第一个已被空结果机械关闭的（无则 null） */
    private static String firstClosedRequestedTool(ChatResponse chatResponse,
                                                   ToolExecutionSupport.ToolExecutionRecorder recorder) {
        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null) {
            return null;
        }
        for (AssistantMessage.ToolCall toolCall : chatResponse.getResult().getOutput().getToolCalls()) {
            if (toolCall.name() != null && recorder.isEmptyClosed(toolCall.name())) {
                return toolCall.name();
            }
        }
        return null;
    }

    /**
     * 模型输入消息快照（调试台「模型输入」查看器）：role + 文本（截断）；
     * assistant 带工具调用时附 toolCalls（模型请求了什么），tool 消息附逐条响应
     * （上一轮工具的真实回传内容——下一轮输入里直接可见）。
     */
    private static List<Map<String, Object>> snapshotMessages(List<Message> messages) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        if (messages == null) {
            return snapshot;
        }
        for (Message message : messages) {
            if (snapshot.size() >= MAX_SNAPSHOT_MESSAGES) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.getMessageType().getValue());
            item.put("content", truncate(message.getText(), MAX_SNAPSHOT_CHARS));
            if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                List<Map<String, String>> toolCalls = new ArrayList<>();
                for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    Map<String, String> call = new LinkedHashMap<>();
                    call.put("name", toolCall.name());
                    call.put("arguments", truncate(toolCall.arguments(), MAX_SNAPSHOT_DETAIL_CHARS));
                    toolCalls.add(call);
                }
                item.put("toolCalls", toolCalls);
            }
            if (message instanceof ToolResponseMessage toolMessage) {
                List<Map<String, String>> responses = new ArrayList<>();
                for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("name", response.name() == null ? "unknown" : response.name());
                    entry.put("content", truncate(response.responseData(), MAX_SNAPSHOT_DETAIL_CHARS));
                    responses.add(entry);
                }
                item.put("responses", responses);
            }
            snapshot.add(item);
        }
        return snapshot;
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }

    /** 超限补录：逐条解析被拦截的工具与上限，通知当前 recorder（无 recorder 时仅日志） */
    private static void recordLimitBreach(List<Message> history) {
        for (Message message : history) {
            if (!(message instanceof ToolResponseMessage toolMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                String content = response.responseData();
                if (!isLimitNotice(content)) {
                    continue;
                }
                String tool = response.name() == null ? "unknown" : response.name();
                log.warn("[mse-ai] 工具 {} 单轮调用超限被拦截: {}", tool, content);
                ToolExecutionSupport.ToolExecutionRecorder recorder = ToolTracePropagation.current();
                if (recorder != null) {
                    recorder.recordLimit(tool, friendlyBreachNotice(tool, content));
                }
            }
        }
    }

    /** 用户可读的拦截说明（轨迹步骤/前端展示用） */
    private static String friendlyBreachNotice(String tool, String content) {
        Matcher matcher = LIMIT_NUMBER.matcher(content);
        int limit = matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        boolean total = content.startsWith(TOTAL_LIMIT_PREFIX);
        if (total) {
            return limit > 0
                    ? "单轮全部工具合计调用上限（" + limit + " 次）已用尽，系统拦截了后续调用"
                    : "单轮全部工具合计调用上限已用尽，系统拦截了后续调用";
        }
        return limit > 0
                ? "工具 " + tool + " 单轮调用上限（" + limit + " 次）已用尽，系统拦截了后续调用"
                : "工具 " + tool + " 单轮调用上限已用尽，系统拦截了后续调用";
    }

    /** 工具响应消息里任一结果命中超限文案前缀即需改写（前缀匹配，正常业务结果不会以该文案开头） */
    private static boolean carriesLimitNotice(Message message) {
        if (!(message instanceof ToolResponseMessage toolMessage)) {
            return false;
        }
        return toolMessage.getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::responseData)
                .anyMatch(FriendlyToolCallingManager::isLimitNotice);
    }

    private static boolean isLimitNotice(String content) {
        return content != null
                && (content.startsWith(PER_TOOL_LIMIT_PREFIX) || content.startsWith(TOTAL_LIMIT_PREFIX));
    }

    /** 重建工具响应消息：超限文案换成中文收尾指令，其余结果原样保留 */
    private static Message rewrite(ToolResponseMessage toolMessage) {
        List<ToolResponseMessage.ToolResponse> responses = toolMessage.getResponses().stream()
                .map(response -> isLimitNotice(response.responseData())
                        ? new ToolResponseMessage.ToolResponse(response.id(), response.name(), FRIENDLY_LIMIT_TEXT)
                        : response)
                .toList();
        return ToolResponseMessage.builder()
                .responses(responses)
                .metadata(toolMessage.getMetadata())
                .build();
    }
}
