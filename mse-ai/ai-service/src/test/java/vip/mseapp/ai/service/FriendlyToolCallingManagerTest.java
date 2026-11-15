package vip.mseapp.ai.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mseapp.ai.support.ToolTracePropagation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用终止链路单测（纯逻辑，不依赖 DB 与模型端点）：
 * 空结果判定矩阵 / 首次即硬终止 / 重复空结果 / 防二次包装 / 混合响应只改写空结果 /
 * 超限文案改写回归（既有行为）/ verbose 轮次快照（调试台时间线数据源）。
 * 走真实 {@link ToolTracePropagation} 传播路径（与生产同步链路一致）。
 */
class FriendlyToolCallingManagerTest {

    /** 典型空结果（成功但数据数组全空，带 tip——正是引导模型循环重试的形态） */
    private static final String EMPTY_RESULT =
            "{\"total\":0,\"tables\":[],\"tip\":\"仅业务前缀表可查；拿不准列名时，用 table（表名，推荐）或 metaTableId 查看该表的列清单\"}";

    private static final String LIMIT_NOTICE =
            "Tool call limit (10) exceeded for tool 'biz_query'. No further calls to this tool are allowed in this turn.";

    private final List<Map<String, Object>> events = new ArrayList<>();
    private final ToolExecutionSupport.ToolExecutionRecorder recorder =
            new ToolExecutionSupport.ToolExecutionRecorder();

    @AfterEach
    void clearPropagation() {
        ToolTracePropagation.clear();
    }

    /** 委托桩：原样返回预置历史（模拟 DefaultToolCallingManager 的执行结果） */
    private FriendlyToolCallingManager manager(List<Message> delegateHistory) {
        return new FriendlyToolCallingManager(new ToolCallingManager() {
            @Override
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
                return List.of();
            }

            @Override
            public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                return ToolExecutionResult.builder()
                        .conversationHistory(delegateHistory)
                        .returnDirect(false)
                        .build();
            }
        });
    }

    /** 绑定事件收集并挂上传播（与生产同步链路 AiChatService.chat 同口径） */
    private ToolExecutionSupport.ToolExecutionRecorder bound() {
        recorder.bind(events::add);
        ToolTracePropagation.set(recorder);
        return recorder;
    }

    private static ToolResponseMessage toolResponse(String tool, String content) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", tool, content)))
                .build();
    }

    private static String responseContent(Message message) {
        return ((ToolResponseMessage) message).getResponses().get(0).responseData();
    }

    // ---------------- 空结果判定矩阵 ----------------

    @Test
    void emptyDataResultMatrix() {
        assertTrue(FriendlyToolCallingManager.isEmptyDataResult(EMPTY_RESULT));
        assertTrue(FriendlyToolCallingManager.isEmptyDataResult("{\"rows\":[]}"));
        // columns 是元数据数组，不参与判定
        assertTrue(FriendlyToolCallingManager.isEmptyDataResult("{\"columns\":[\"a\"],\"rows\":[]}"));
        // 任一数据数组非空即不算空结果
        assertFalse(FriendlyToolCallingManager.isEmptyDataResult("{\"rows\":[{\"total\":128}]}"));
        assertFalse(FriendlyToolCallingManager.isEmptyDataResult("{\"tables\":[],\"rows\":[{\"a\":1}]}"));
        // 无任何数据数组字段（纯标量结果）不算
        assertFalse(FriendlyToolCallingManager.isEmptyDataResult("{\"total\":0}"));
        // 非 JSON / 空串 / null 不算
        assertFalse(FriendlyToolCallingManager.isEmptyDataResult("plain text"));
        assertFalse(FriendlyToolCallingManager.isEmptyDataResult(""));
        assertFalse(FriendlyToolCallingManager.isEmptyDataResult(null));
        // 已包装的终止指令（非 JSON）不得再命中判定——防双重叠加
        assertFalse(FriendlyToolCallingManager.isEmptyDataResult(
                FriendlyToolCallingManager.hardNoDataText("biz_query", EMPTY_RESULT)));
    }

    @Test
    void hardNoDataTextLeadsWithDirectiveAndKeepsOriginal() {
        String text = FriendlyToolCallingManager.hardNoDataText("biz_query", EMPTY_RESULT);
        assertTrue(text.startsWith("【系统指令】"), "终止指令置顶最醒目");
        assertTrue(text.contains("请勿再调用任何工具重试"));
        assertTrue(text.contains("更换 keyword"), "点名换 keyword 重试也在禁止之列");
        assertTrue(text.contains(EMPTY_RESULT), "原始返回内嵌保留（供作答引用 tip）");
    }

    // ---------------- 空结果首次即硬终止 ----------------

    @Test
    void firstEmptyResultIsHardTerminatedAndRecorderNotified() {
        bound();
        FriendlyToolCallingManager manager = manager(List.of(
                new UserMessage("我有多少审批记录"), toolResponse("biz_query", EMPTY_RESULT)));

        ToolExecutionResult result = manager.executeToolCalls(prompt(), chatResponse());

        List<Message> history = result.conversationHistory();
        assertEquals(2, history.size());
        assertInstanceOf(ToolResponseMessage.class, history.get(1));
        String content = responseContent(history.get(1));
        assertTrue(content.startsWith("【系统指令】"), "首次空结果即整条替换为终止指令");
        assertTrue(content.contains(EMPTY_RESULT), "原始返回内嵌保留");
        assertInstanceOf(UserMessage.class, history.get(0));
        assertEquals("我有多少审批记录", ((UserMessage) history.get(0)).getText(), "其余消息原样保留");
        // recorder 收到 nodata 轨迹事件（first=true）
        assertEquals(1, events.size());
        assertEquals("nodata", events.get(0).get("phase"));
        assertEquals("biz_query", events.get(0).get("tool"));
        assertEquals(Boolean.TRUE, events.get(0).get("first"));
    }

    @Test
    void emptyResultTerminatesEvenWithoutRecorder() {
        // 无 recorder（传播未覆盖的链路）：仍然硬终止，只是不留轨迹事件
        FriendlyToolCallingManager manager = manager(List.of(toolResponse("biz_query", EMPTY_RESULT)));

        ToolExecutionResult result = manager.executeToolCalls(prompt(), chatResponse());

        assertTrue(responseContent(result.conversationHistory().get(0)).startsWith("【系统指令】"));
    }

    // ---------------- 重复空结果：仍硬终止，first=false ----------------

    @Test
    void repeatedEmptyResultStillTerminatedWithFirstFalse() {
        bound();
        FriendlyToolCallingManager manager = manager(List.of(toolResponse("biz_query", EMPTY_RESULT)));

        manager.executeToolCalls(prompt(), chatResponse());
        manager.executeToolCalls(prompt(), chatResponse());

        List<Map<String, Object>> nodataEvents = events.stream()
                .filter(e -> "nodata".equals(e.get("phase"))).toList();
        assertEquals(2, nodataEvents.size());
        assertEquals(Boolean.TRUE, nodataEvents.get(0).get("first"));
        assertEquals(Boolean.FALSE, nodataEvents.get(1).get("first"), "同工具第二次空结果标记为重复");
    }

    // ---------------- 混合响应：只改写空结果那条 ----------------

    @Test
    void mixedResponsesOnlyEmptyRewritten() {
        bound();
        ToolResponseMessage mixed = ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "biz_query", EMPTY_RESULT),
                        new ToolResponseMessage.ToolResponse("c2", "other_tool", "{\"rows\":[{\"a\":1}]}")))
                .build();
        FriendlyToolCallingManager manager = manager(List.of(mixed));

        ToolExecutionResult result = manager.executeToolCalls(prompt(), chatResponse());

        ToolResponseMessage rewritten = (ToolResponseMessage) result.conversationHistory().get(0);
        assertEquals(2, rewritten.getResponses().size());
        assertTrue(rewritten.getResponses().get(0).responseData().startsWith("【系统指令】"));
        assertEquals("{\"rows\":[{\"a\":1}]}", rewritten.getResponses().get(1).responseData());
    }

    // ---------------- 超限文案改写（既有行为回归） ----------------

    @Test
    void limitNoticeStillRewrittenToChinese() {
        bound();
        FriendlyToolCallingManager manager = manager(List.of(toolResponse("biz_query", LIMIT_NOTICE)));

        ToolExecutionResult result = manager.executeToolCalls(prompt(), chatResponse());

        String content = responseContent(result.conversationHistory().get(0));
        assertTrue(content.startsWith("本轮工具调用次数已达上限"), "英文超限文案改写为中文收尾指令");
        assertFalse(content.startsWith("Tool call limit"));
        assertEquals("limit", events.get(0).get("phase"), "超限轨迹事件保留");
    }

    // ---------------- verbose 轮次快照（调试台时间线数据源） ----------------

    @Test
    void verboseTurnSnapshotsEmitted() {
        bound().setVerbose(true);
        FriendlyToolCallingManager manager = manager(List.of(
                new UserMessage("我有多少审批记录"), toolResponse("biz_query", "{\"rows\":[{\"total\":128}]}")));

        manager.executeToolCalls(prompt(), chatResponse());

        assertEquals(List.of("turn", "turn-end"),
                events.stream().map(e -> String.valueOf(e.get("phase"))).toList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entry = (List<Map<String, Object>>) events.get(0).get("messages");
        assertEquals("user", entry.get(0).get("role"), "轮 1 输入 = 该轮模型实际收到的消息");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exit = (List<Map<String, Object>>) events.get(1).get("messages");
        assertEquals(2, exit.size(), "出口历史 = 下一轮输入快照");
        assertEquals("tool", exit.get(1).get("role"));
    }

    // ---------------- 空结果关闭 → 异常通道终止循环 ----------------

    @Test
    void closedToolRequestedAgainThrowsLimitExceptionWithHistory() {
        bound();
        recorder.markEmptyResult("biz_query");
        recorder.markEmptyResult("biz_query");
        assertTrue(recorder.isEmptyClosed("biz_query"));
        FriendlyToolCallingManager manager = manager(List.of(toolResponse("biz_query", EMPTY_RESULT)));

        Prompt prompt = prompt();
        org.springframework.ai.model.tool.ToolCallLimitExceededException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.ai.model.tool.ToolCallLimitExceededException.class,
                        () -> manager.executeToolCalls(prompt, toolCallResponse("biz_query")));

        // partialResult 携带完整历史（末条工具响应的文本将作为异常收尾的回答）
        assertEquals(prompt.getInstructions(),
                ex.getPartialToolExecutionResult().conversationHistory());
    }

    @Test
    void closedToolWithoutRecorderDoesNotThrow() {
        // 无 recorder（传播未覆盖）：维持既有短路/改写行为，不抛异常
        FriendlyToolCallingManager manager = manager(List.of(toolResponse("biz_query", EMPTY_RESULT)));

        ToolExecutionResult result = manager.executeToolCalls(prompt(), toolCallResponse("biz_query"));

        assertEquals(1, result.conversationHistory().size(), "正常执行不终止");
    }

    @Test
    void nonClosedToolDoesNotThrow() {
        bound();
        recorder.markEmptyResult("biz_query"); // 仅 1 次，未达关闭阈值
        FriendlyToolCallingManager manager = manager(List.of(toolResponse("biz_query", EMPTY_RESULT)));

        ToolExecutionResult result = manager.executeToolCalls(prompt(), toolCallResponse("biz_query"));

        assertEquals(1, result.conversationHistory().size(), "未关闭的工具请求正常执行");
    }

    // ---------------- 测试夹具 ----------------

    private static Prompt prompt() {
        return new Prompt(List.of(new UserMessage("我有多少审批记录")));
    }

    private static ChatResponse chatResponse() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("查表"))));
    }

    /** 模拟模型请求工具调用的响应（AssistantMessage 携带 toolCalls） */
    private static ChatResponse toolCallResponse(String tool) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", tool, "{\"action\":\"meta_search\"}");
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }
}
