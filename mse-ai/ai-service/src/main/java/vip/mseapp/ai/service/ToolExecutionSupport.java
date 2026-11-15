package vip.mseapp.ai.service;

import tools.jackson.databind.JsonNode;
import vip.mseapp.ai.api.dto.AiToolResult;
import vip.mseapp.ai.support.AiJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 工具执行观察：把每次工具调用（参数/结果/耗时）实时上报给对话链路，
 * 并对「含 columns+rows 的结构化结果」提取数据集载荷（SSE 推送与消息 ext 落库共用）。
 *
 * <p>recorder 每次对话一个（请求线程创建，事件发生在 Reactor 线程，全部线程安全）：
 * SSE 建立后 {@link ToolExecutionRecorder#bind} 消费者，模型流内工具执行即实时推送；
 * 供完成时「绑定了工具但模型未调用」判定（executionCount / resolvedCount）。
 *
 * <p>除事件外还收集逐步轨迹 {@link Step}（start/result 按 seq 配对，超限拦截经
 * {@link #recordLimit} 补录），完成时随消息 ext 落库（toolCalls），前端历史回放恢复。
 */
public final class ToolExecutionSupport {

    /** 数据集行数上限（数据源侧 execute 已限 200 行，此处再收紧推送/落库体积） */
    private static final int MAX_DATASET_ROWS = 100;

    /** 数据集单元格字符上限 */
    private static final int MAX_CELL_CHARS = 200;

    /** 轨迹步数上限（连环重试的异常场景截断，事件推送不受限） */
    private static final int MAX_STEPS = 60;

    /** 轮次快照留存轮数上限（ext 落库体积护栏；快照内容本身已有 4000/2000 字符截断） */
    private static final int MAX_TURN_SNAPSHOTS = 12;

    /**
     * 空结果机械关闭阈值：同一工具累计空结果达该次数即关闭（后续调用短路直返，不再执行）。
     * 真实环境实测：模型在单条助手消息里并行规划 22 个换 keyword 的调用，终止指令随结果
     * 返回时同批调用已全部执行完毕——指令式软停止拦不住同批调用，机械短路是唯一保证。
     */
    static final int EMPTY_RESULT_CLOSE_THRESHOLD = 2;

    /** 轨迹入参留存字符上限（与 ai_tool_log 审计同宽） */
    private static final int MAX_ARGUMENT_CHARS = 2000;

    /** 轨迹返回结果留存字符上限（与 ai_tool_log 审计同宽） */
    private static final int MAX_RESULT_CHARS = 2000;

    /** 轨迹失败原因留存字符上限 */
    private static final int MAX_ERROR_CHARS = 500;

    private ToolExecutionSupport() {
    }

    /** 工具执行观察者（解析回调时闭包携带；方法均可选实现） */
    public interface ToolExecutionListener {

        default void onStart(String toolCode, String argumentsJson) {
        }

        default void onComplete(String toolCode, String argumentsJson, AiToolResult result, long elapsedMs) {
        }
    }

    /** 数据集载荷（工具结果含 columns+rows 数组时提取，天然覆盖 data_query execute 与同构工具） */
    public record DatasetPayload(String sql, List<String> columns, List<Map<String, Object>> rows,
                                 int rowCount, boolean hasMore) {
    }

    /**
     * 单次工具调用轨迹（start/result 按 seq 配对；status：running/success/fail/limit）。
     * turn = 所属模型轮次（1 起，0 = 旧数据/未知）：FriendlyToolCallingManager 在每轮
     * 工具执行前登记轮次边界，其后启动的工具步骤归属该轮。
     */
    public static final class Step {

        private final int seq;
        private final String tool;
        private volatile String arguments;
        private volatile String status;
        private volatile Long elapsedMs;
        private volatile String error;
        private volatile DatasetPayload dataset;
        private volatile String result;
        private volatile int turn;

        Step(int seq, String tool, String arguments) {
            this.seq = seq;
            this.tool = tool;
            this.arguments = arguments;
            this.status = "running";
        }

        public int getSeq() {
            return seq;
        }

        public String getTool() {
            return tool;
        }

        public String getArguments() {
            return arguments;
        }

        public String getStatus() {
            return status;
        }

        public Long getElapsedMs() {
            return elapsedMs;
        }

        public String getError() {
            return error;
        }

        public DatasetPayload getDataset() {
            return dataset;
        }

        public String getResult() {
            return result;
        }

        public int getTurn() {
            return turn;
        }
    }

    /**
     * 每次对话一个 recorder：实时转发执行事件、统计执行次数与已解析工具数、
     * 收集数据集载荷与逐步轨迹（完成回调写消息 ext 用）。
     *
     * <p>verbose 模式（仅调试通道开启）额外登记模型轮次边界
     * （{@code FriendlyToolCallingManager} 在每轮工具执行前后回调
     * {@link #recordTurnBegin}/{@link #recordTurnEnd}），SSE 下发 phase=turn/turn-end
     * 事件（携带该轮模型实际输入/下一轮输入的消息快照），调试台据此还原全过程时间线。
     */
    public static class ToolExecutionRecorder implements ToolExecutionListener {

        private final Queue<Map<String, Object>> pendingEvents = new ConcurrentLinkedQueue<>();
        private final List<DatasetPayload> datasets = new CopyOnWriteArrayList<>();
        private final List<Step> steps = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> turnSnapshots = new CopyOnWriteArrayList<>();
        private final java.util.Set<String> noDataDirected = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** 同工具累计空结果计数（达 {@link #EMPTY_RESULT_CLOSE_THRESHOLD} 即机械关闭） */
        private final Map<String, Integer> emptyResultCounts = new java.util.concurrent.ConcurrentHashMap<>();
        /** 已机械关闭的工具（本对话内后续调用短路直返，跨批次持久） */
        private final java.util.Set<String> closedEmptyTools = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final AtomicInteger executionCount = new AtomicInteger();
        private final AtomicInteger resolvedCount = new AtomicInteger();
        private final AtomicInteger seqGen = new AtomicInteger();
        private final AtomicInteger turnGen = new AtomicInteger();
        private final long createdAt = System.currentTimeMillis();
        private volatile Consumer<Map<String, Object>> sink;
        private volatile boolean verbose;

        /** 绑定事件消费者（SSE 建立后、模型调用前绑定；此前缓存的事件立即补发） */
        public void bind(Consumer<Map<String, Object>> consumer) {
            this.sink = consumer;
            Map<String, Object> event;
            while ((event = pendingEvents.poll()) != null) {
                consumer.accept(event);
            }
        }

        /** 开启详细轨迹（轮次边界 + 消息快照；仅调试通道，对话/RPC 通道保持轻量） */
        public void setVerbose(boolean verbose) {
            this.verbose = verbose;
        }

        public boolean isVerbose() {
            return verbose;
        }

        /** 已登记的模型轮次边界数（总轮次 = 边界数 + 1：最后一轮不再请求工具、无边界） */
        public int turnCount() {
            return turnGen.get();
        }

        /** 实际执行的工具调用次数（含失败；超限拦截不计） */
        public int executionCount() {
            return executionCount.get();
        }

        /** 成功解析的工具回调数（reportUnusedTools 区分「绑定未调用」与「未解析成功」） */
        public int resolvedCount() {
            return resolvedCount.get();
        }

        /** 登记成功解析的工具回调数（AgentRuntime 挂载后调用，供未调用判定区分「工具不可用」） */
        public void markResolved(int count) {
            resolvedCount.addAndGet(count);
        }

        public boolean hasExecutions() {
            return executionCount.get() > 0;
        }

        /** 本次对话收集到的数据集载荷（ext 落库用） */
        public List<DatasetPayload> datasets() {
            return List.copyOf(datasets);
        }

        /** 本次对话的逐步工具轨迹（ext 落库用；超上限截断） */
        public List<Step> steps() {
            return List.copyOf(steps);
        }

        /**
         * 轮次边界·开始（模型第 N 轮输出完毕并请求工具，工具执行前回调）：
         * entryMessages = 该轮模型实际收到的完整输入消息快照
         * （系统提示词 + RAG 注入 + 记忆/历史 + 工具回传，真实 prompt）。
         * 同时留存快照（verbose 模式，ext.turns 落库回放用，超上限截断）。
         */
        public void recordTurnBegin(List<Map<String, Object>> entryMessages) {
            int index = turnGen.incrementAndGet();
            if (turnSnapshots.size() < MAX_TURN_SNAPSHOTS) {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("index", index);
                snapshot.put("elapsedMs", System.currentTimeMillis() - createdAt);
                snapshot.put("messages", entryMessages);
                turnSnapshots.add(snapshot);
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("phase", "turn");
            event.put("index", index);
            event.put("elapsedMs", System.currentTimeMillis() - createdAt);
            event.put("messages", entryMessages);
            emit(event);
        }

        /** 轮次边界·结束（本轮工具执行完毕）：exitMessages = 下一轮模型输入快照（本轮出口历史） */
        public void recordTurnEnd(List<Map<String, Object>> exitMessages) {
            if (!turnSnapshots.isEmpty()) {
                turnSnapshots.get(turnSnapshots.size() - 1).put("next", exitMessages);
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("phase", "turn-end");
            event.put("index", turnGen.get());
            event.put("messages", exitMessages);
            emit(event);
        }

        /** 本次对话的轮次输入快照（ext.turns 落库回放用，verbose 模式才收集） */
        public List<Map<String, Object>> turnSnapshots() {
            return List.copyOf(turnSnapshots);
        }

        @Override
        public void onStart(String toolCode, String argumentsJson) {
            int seq = seqGen.incrementAndGet();
            String arguments = truncate(argumentsJson, MAX_ARGUMENT_CHARS);
            if (steps.size() < MAX_STEPS) {
                Step step = new Step(seq, toolCode, arguments);
                step.turn = turnGen.get();
                steps.add(step);
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("seq", seq);
            event.put("tool", toolCode);
            event.put("phase", "start");
            event.put("turn", turnGen.get());
            JsonNode args = AiJson.tryParse(arguments);
            event.put("arguments", args != null ? args : arguments);
            emit(event);
        }

        @Override
        public void onComplete(String toolCode, String argumentsJson, AiToolResult result, long elapsedMs) {
            executionCount.incrementAndGet();
            DatasetPayload dataset = extractDataset(argumentsJson, result);
            String resultText = truncate(result.getContent(), MAX_RESULT_CHARS);
            Step step = findRunningStep(toolCode);
            if (step != null) {
                step.status = result.isSuccess() ? "success" : "fail";
                step.elapsedMs = elapsedMs;
                step.dataset = dataset;
                step.result = resultText;
                if (!result.isSuccess()) {
                    step.error = truncate(result.getContent(), MAX_ERROR_CHARS);
                }
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("seq", step != null ? step.seq : seqGen.incrementAndGet());
            event.put("tool", toolCode);
            event.put("phase", "result");
            event.put("success", result.isSuccess());
            event.put("elapsedMs", elapsedMs);
            if (resultText != null) {
                event.put("result", resultText);
            }
            if (!result.isSuccess() && result.getContent() != null) {
                event.put("error", truncate(result.getContent(), MAX_ERROR_CHARS));
            }
            if (dataset != null) {
                event.put("dataset", dataset);
            }
            emit(event);
        }

        /**
         * 超限拦截补录：{@code FriendlyToolCallingManager} 在 ToolCallingManager 内部拦截
         * 超限调用（不经过 ToolCallback 包装层，onStart/onComplete 均不可见），改写文案时
         * 经 {@code ToolTracePropagation} 取当前 recorder 调此处——轨迹与 SSE 才能看到「被拦下了」。
         */
        public void recordLimit(String toolCode, String message) {
            int seq = seqGen.incrementAndGet();
            if (steps.size() < MAX_STEPS) {
                Step step = new Step(seq, toolCode, null);
                step.status = "limit";
                step.error = truncate(message, MAX_ERROR_CHARS);
                step.turn = turnGen.get();
                steps.add(step);
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("seq", seq);
            event.put("tool", toolCode);
            event.put("phase", "limit");
            event.put("turn", turnGen.get());
            event.put("message", message);
            emit(event);
        }

        /**
         * 空结果终止引导标记：该工具已因「成功但无数据」被引导过一次
         * （返回 true = 首次，false = 模型无视引导再次空结果，改硬终止）。
         */
        public boolean markNoDataDirected(String toolCode) {
            return noDataDirected.add(toolCode);
        }

        /** 空结果终止轨迹事件（SSE phase=nodata，调试台/工作台展示「空结果已终止工具循环」） */
        public void recordNoData(String toolCode, boolean first) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("phase", "nodata");
            event.put("tool", toolCode);
            event.put("first", first);
            event.put("message", first
                    ? "工具 " + toolCode + " 调用成功但返回空数据，系统已终止工具循环（空结果不重试），模型将如实作答"
                    : "工具 " + toolCode + " 此前已返回空数据仍被重复调用，系统已再次终止工具循环");
            emit(event);
        }

        /** 该工具是否已因累计空结果被机械关闭（回调层短路判定，跨批次持久） */
        public boolean isEmptyClosed(String toolCode) {
            return closedEmptyTools.contains(toolCode);
        }

        /**
         * 空结果计数：同一工具累计空结果达阈值即机械关闭（返回 true = 本次刚关闭）。
         * 关闭后本对话内该工具的后续调用由回调层短路直返终止指令、不再执行——
         * 指令式软停止对「单条助手消息并行规划多个调用」无效（指令随结果返回时
         * 同批调用已全部执行完毕），回调层闭包持有 recorder 跨批次持久，
         * 机械短路是唯一不依赖模型服从的保证。
         */
        public boolean markEmptyResult(String toolCode) {
            int count = emptyResultCounts.merge(toolCode, 1, Integer::sum);
            if (count < EMPTY_RESULT_CLOSE_THRESHOLD || !closedEmptyTools.add(toolCode)) {
                return false;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("phase", "nodata");
            event.put("tool", toolCode);
            event.put("first", Boolean.FALSE);
            event.put("message", "工具 " + toolCode + " 累计 " + count + " 次空结果，已机械关闭："
                    + "本对话内后续调用不再执行、直接返回终止指令");
            emit(event);
            return true;
        }

        /** 从尾向前找该工具最近一个 running 步骤（同名工具连环调用按序自然配对） */
        private Step findRunningStep(String toolCode) {
            synchronized (this) {
                for (int i = steps.size() - 1; i >= 0; i--) {
                    Step step = steps.get(i);
                    if ("running".equals(step.status) && step.tool.equals(toolCode)) {
                        return step;
                    }
                }
            }
            return null;
        }

        private void emit(Map<String, Object> event) {
            Consumer<Map<String, Object>> consumer = sink;
            if (consumer != null) {
                consumer.accept(event);
            } else {
                pendingEvents.add(event);
            }
        }

        /** 结果 JSON 含 columns+rows 数组即视为数据集；行数/单元格按上限截断 */
        private DatasetPayload extractDataset(String argumentsJson, AiToolResult result) {
            if (!result.isSuccess() || result.getContent() == null) {
                return null;
            }
            try {
                JsonNode json = AiJson.tryParse(result.getContent());
                if (json == null || !json.has("columns") || !json.has("rows")
                        || !json.get("columns").isArray() || !json.get("rows").isArray()) {
                    return null;
                }
                List<String> columns = new ArrayList<>();
                json.get("columns").forEach(column -> columns.add(column.asText()));
                JsonNode args = AiJson.tryParse(argumentsJson);
                String sql = args != null && args.has("sql") && !args.get("sql").isNull()
                        ? args.get("sql").asText() : null;
                boolean hasMore = json.path("hasMore").asBoolean(false);
                int total = json.get("rows").size();
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 0; i < Math.min(total, MAX_DATASET_ROWS); i++) {
                    JsonNode row = json.get("rows").get(i);
                    Map<String, Object> item = new LinkedHashMap<>();
                    for (String column : columns) {
                        item.put(column, simplify(row.get(column)));
                    }
                    rows.add(item);
                }
                if (total > MAX_DATASET_ROWS) {
                    hasMore = true;
                }
                DatasetPayload payload = new DatasetPayload(sql, columns, rows, rows.size(), hasMore);
                datasets.add(payload);
                return payload;
            } catch (Exception e) {
                return null;
            }
        }

        /** 单元格值 JSON 友好化：数值/布尔保留类型，文本截断，嵌套结构转 JSON 字符串 */
        private static Object simplify(JsonNode value) {
            if (value == null || value.isNull() || value.isMissingNode()) {
                return null;
            }
            if (value.isNumber()) {
                return value.numberValue();
            }
            if (value.isBoolean()) {
                return value.booleanValue();
            }
            String text = value.isTextual() ? value.textValue() : value.toString();
            return text.length() <= MAX_CELL_CHARS ? text : text.substring(0, MAX_CELL_CHARS) + "…";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
}
