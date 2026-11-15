package vip.mseapp.ai.api.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import vip.mseapp.ai.api.RemoteAiTool;
import vip.mseapp.ai.api.dto.AiToolDescriptor;
import vip.mseapp.ai.api.dto.AiToolResult;

/**
 * 多工具导出门面：同一服务（Dubbo group）只能导出一个 {@link RemoteAiTool} 实现
 * （mse-ai 按 interface+group 寻址，重复导出会被随机命中），多工具经本门面按
 * mse-ai 传入的 toolCode 内部分发；工具描述仍由容器内各 RemoteAiTool Bean 经
 * AiToolRegistrationPusher 逐个推送登记，本门面不参与描述登记。
 */
public class DispatchingAiTool implements RemoteAiTool {

    private final Map<String, RemoteAiTool> tools = new LinkedHashMap<>();

    public DispatchingAiTool(List<RemoteAiTool> tools) {
        for (RemoteAiTool tool : tools) {
            this.tools.put(tool.describe().getToolCode(), tool);
        }
    }

    @Override
    public AiToolDescriptor describe() {
        // 导出通道仅消费 invoke；工具描述由推送器按容器内各工具 Bean 逐个收集，本方法不会被走通
        return tools.values().iterator().next().describe();
    }

    @Override
    public AiToolResult invoke(String argumentsJson) {
        // mse-ai 泛化调用固定走两参 invoke；一参调用无法定位工具，明确失败
        AiToolResult result = new AiToolResult();
        result.setSuccess(false);
        result.setContent("多工具导出门面须携带 toolCode 调用（invoke(toolCode, argumentsJson)）");
        return result;
    }

    @Override
    public AiToolResult invoke(String toolCode, String argumentsJson) {
        RemoteAiTool tool = toolCode == null ? null : tools.get(toolCode);
        if (tool == null) {
            AiToolResult result = new AiToolResult();
            result.setSuccess(false);
            result.setContent("未知的工具编码：" + toolCode);
            return result;
        }
        return tool.invoke(argumentsJson);
    }
}
