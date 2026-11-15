package vip.mseapp.ai.workflow.exec;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.service.AiModelFactory;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.List;
import java.util.Map;

/**
 * 参数提取节点：LLM 按字段清单从输入中抽取结构化参数，
 * 每个字段作为独立输出变量（数据分析场景提取时间范围/维度/指标等）。
 */
@Component
public class ExtractorNodeExecutor implements NodeExecutor {

    private final AiModelFactory modelFactory;

    public ExtractorNodeExecutor(AiModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    @Override
    public String type() {
        return "extractor";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        List<Map<String, Object>> fields = node.listMap("fields");
        if (fields.isEmpty()) {
            throw new ServiceException(400, "节点「" + node.title() + "」未配置抽取字段");
        }
        String query = VarRenderer.render(node.str("query", "{{sys.query}}"), ctx.resolver());
        StringBuilder prompt = new StringBuilder(
                "从用户输入中抽取以下字段，返回且仅返回一个 JSON 对象（不要输出其他内容），缺失字段值为空字符串：\n");
        for (Map<String, Object> field : fields) {
            prompt.append("- ").append(field.getOrDefault("name", ""))
                    .append("（").append(field.getOrDefault("type", "string")).append("）：")
                    .append(field.getOrDefault("description", "")).append('\n');
        }
        prompt.append("\n用户输入：").append(query);

        Long modelId = node.lng("modelId", env.defaultModelId());
        ChatModel chatModel = modelFactory.getChatModel(modelId);
        String answer = ChatClient.builder(chatModel).build().prompt().user(prompt.toString())
                .options(org.springframework.ai.chat.prompt.ChatOptions.builder().temperature(0.0))
                .call().content();
        Map<String, Object> extracted = extractJson(answer);
        for (Map<String, Object> field : fields) {
            String name = String.valueOf(field.getOrDefault("name", ""));
            Object value = extracted.get(name);
            ctx.setOutput(node.id(), name, value == null ? "" : VarRenderer.display(value));
        }
    }

    /** 宽容解析：剥掉 markdown 代码栅栏后取第一个 JSON 对象 */
    private static Map<String, Object> extractJson(String answer) {
        if (answer == null || answer.isBlank()) {
            return Map.of();
        }
        String text = answer.trim()
                .replaceAll("^```(json)?\\s*", "")
                .replaceAll("\\s*```$", "");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        try {
            return AiJson.toMap(text);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
