package vip.mseapp.ai.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空结果机械关闭语义单测（回调层短路的判定依据）：
 * recorder 闭包跨批次持久、不依赖模型服从与线程传播——真实环境实测模型在单条
 * 助手消息里并行规划 22 个换 keyword 的调用，指令式软停止拦不住同批调用，
 * 同工具累计空结果达阈值即机械关闭是唯一机械保证。
 */
class ToolExecutionRecorderTest {

    @Test
    void emptyResultsCloseToolAtThreshold() {
        ToolExecutionSupport.ToolExecutionRecorder recorder = new ToolExecutionSupport.ToolExecutionRecorder();
        List<Map<String, Object>> events = new ArrayList<>();
        recorder.bind(events::add);

        assertFalse(recorder.isEmptyClosed("biz_query"));
        assertFalse(recorder.markEmptyResult("biz_query"), "第 1 次空结果不关闭（终止指令已随返回下发）");
        assertFalse(recorder.isEmptyClosed("biz_query"));
        assertTrue(recorder.markEmptyResult("biz_query"), "第 2 次空结果触发机械关闭");
        assertTrue(recorder.isEmptyClosed("biz_query"), "关闭后本对话内后续调用短路直返");
        // 关闭事件（SSE + 调试台提示条）
        assertEquals(1, events.size());
        assertEquals("nodata", events.get(0).get("phase"));
        assertEquals("biz_query", events.get(0).get("tool"));
        assertEquals(Boolean.FALSE, events.get(0).get("first"));
        assertTrue(String.valueOf(events.get(0).get("message")).contains("已机械关闭"));
        // 已关闭后重复标记：不重复关闭、不重复发事件
        assertFalse(recorder.markEmptyResult("biz_query"));
        assertEquals(1, events.size());
    }

    @Test
    void closureIsPerToolAndThresholdScoped() {
        ToolExecutionSupport.ToolExecutionRecorder recorder = new ToolExecutionSupport.ToolExecutionRecorder();
        recorder.bind(e -> { });

        recorder.markEmptyResult("biz_query");
        recorder.markEmptyResult("biz_query");
        assertTrue(recorder.isEmptyClosed("biz_query"));
        assertFalse(recorder.isEmptyClosed("data_query"), "其他工具不受影响");
        recorder.markEmptyResult("data_query");
        assertFalse(recorder.isEmptyClosed("data_query"), "仅 1 次空结果未达阈值");
    }
}
