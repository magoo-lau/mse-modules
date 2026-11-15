package vip.mseapp.ai.workflow.exec;

import com.hubspot.jinjava.Jinjava;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.Map;

/**
 * 模板转换节点：Jinja2 语法（Jinjava），变量经嵌套视图取值（{@code {{ llm1.text }}}）。
 */
@Component
public class TemplateNodeExecutor implements NodeExecutor {

    private final Jinjava jinjava = new Jinjava();

    @Override
    public String type() {
        return "template";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        String template = node.str("template", "");
        if (template.isBlank()) {
            throw new ServiceException(400, "节点「" + node.title() + "」模板为空");
        }
        Map<String, Object> nested = ctx.nested();
        String rendered;
        try {
            rendered = jinjava.render(template, nested);
        } catch (Exception e) {
            throw new ServiceException(400, "节点「" + node.title() + "」模板渲染失败: " + e.getMessage());
        }
        ctx.setOutput(node.id(), "output", rendered);
    }
}
