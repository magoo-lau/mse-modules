package vip.mseapp.ai.tool;

import org.springframework.stereotype.Component;
import vip.mseapp.ai.api.RemoteAiTool;
import vip.mseapp.ai.api.dto.AiToolDescriptor;
import vip.mseapp.ai.api.dto.AiToolResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 内置示例工具：当前时间。
 *
 * <p>演示本地工具形态——实现 {@link RemoteAiTool} 并注册为 Spring Bean，
 * 平台自动收集（单体与微服务形态行为一致）。
 */
@Component
public class CurrentTimeTool implements RemoteAiTool {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public AiToolDescriptor describe() {
        AiToolDescriptor descriptor = new AiToolDescriptor();
        descriptor.setToolCode("current_time");
        descriptor.setName("getCurrentTime");
        descriptor.setDescription("获取服务器当前时间（yyyy-MM-dd HH:mm:ss），回答与当前时刻有关的问题时使用");
        descriptor.setParametersJsonSchema("{\"type\":\"object\",\"properties\":{},\"required\":[]}");
        return descriptor;
    }

    @Override
    public AiToolResult invoke(String argumentsJson) {
        return AiToolResult.ok(LocalDateTime.now().format(FORMATTER));
    }
}
