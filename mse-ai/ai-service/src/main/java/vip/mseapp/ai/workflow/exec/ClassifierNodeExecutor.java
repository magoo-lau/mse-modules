package vip.mseapp.ai.workflow.exec;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.service.AiModelFactory;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.List;
import java.util.Map;

/**
 * 问题分类节点：单次 LLM 调用把输入归入预定义分类之一，按命中序号路由 {@code case<i>}。
 * 输出 class_name / class_id；解析失败归入第一类（保证分支可达）。
 */
@Component
public class ClassifierNodeExecutor implements NodeExecutor {

    private final AiModelFactory modelFactory;

    public ClassifierNodeExecutor(AiModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    @Override
    public String type() {
        return "classifier";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        List<Map<String, Object>> classes = node.listMap("classes");
        if (classes.isEmpty()) {
            throw new ServiceException(400, "节点「" + node.title() + "」未配置分类");
        }
        String query = VarRenderer.render(node.str("query", "{{sys.query}}"), ctx.resolver());
        StringBuilder prompt = new StringBuilder("你是文本分类器。把用户输入归入以下分类之一，只输出分类序号数字，不要输出其他内容：\n");
        for (int i = 0; i < classes.size(); i++) {
            prompt.append(i).append(". ").append(classes.get(i).getOrDefault("name", ""))
                    .append("：").append(classes.get(i).getOrDefault("description", "")).append('\n');
        }
        prompt.append("\n用户输入：").append(query);

        Long modelId = node.lng("modelId", env.defaultModelId());
        ChatModel chatModel = modelFactory.getChatModel(modelId);
        String answer = ChatClient.builder(chatModel).build().prompt().user(prompt.toString())
                .options(org.springframework.ai.chat.prompt.ChatOptions.builder().temperature(0.1))
                .call().content();
        int index = parseIndex(answer, classes.size());
        ctx.setBranch(node.id(), "case" + index);
        ctx.setOutput(node.id(), "class_id", String.valueOf(index));
        ctx.setOutput(node.id(), "class_name", String.valueOf(classes.get(index).getOrDefault("name", "")));
    }

    private static int parseIndex(String answer, int size) {
        if (answer == null) {
            return 0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(answer);
        if (matcher.find()) {
            int index = Integer.parseInt(matcher.group());
            if (index >= 0 && index < size) {
                return index;
            }
        }
        return 0;
    }
}
