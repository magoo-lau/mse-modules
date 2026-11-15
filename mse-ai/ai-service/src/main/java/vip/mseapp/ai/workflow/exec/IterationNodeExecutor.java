package vip.mseapp.ai.workflow.exec;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.workflow.AgentWorkflowEngine;
import vip.mseapp.ai.workflow.FlowCancelledException;
import vip.mseapp.ai.workflow.FlowProperties;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowGraph;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 迭代节点：对数组变量逐项顺序执行内嵌子图（data.graph），
 * 每项的输出取 outputSelector 引用（缺省为子图首个 answer/end 的文本）。
 * 子图内通过 start 节点自定义变量 item/index 访问当前条目。
 */
@Component
public class IterationNodeExecutor implements NodeExecutor {

    private final ObjectProvider<AgentWorkflowEngine> engineProvider;

    private final FlowProperties properties;

    public IterationNodeExecutor(ObjectProvider<AgentWorkflowEngine> engineProvider, FlowProperties properties) {
        this.engineProvider = engineProvider;
        this.properties = properties;
    }

    @Override
    public String type() {
        return "iteration";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        FlowGraph subGraph = parseSubGraph(node);
        subGraph.requireValid();
        List<Object> items = resolveItems(node, ctx);
        int maxItems = node.intg("maxItems", properties.getIterationMaxItems());
        if (items.size() > maxItems) {
            throw new ServiceException(400, "节点「" + node.title() + "」迭代条目数 " + items.size()
                    + " 超过上限 " + maxItems);
        }
        String outputSelector = node.str("outputSelector", "");
        List<Object> collected = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            // 取消检查点：两次 runSubgraph 之间的间隙（子图内检查点覆盖不到这里）
            if (env.isCancelled()) {
                throw new FlowCancelledException();
            }
            WorkflowContext child = new WorkflowContext();
            // 迭代体可见父级变量 + 当前条目（item 为原始对象，模板渲染时序列化）
            child.inherit(ctx);
            Object item = items.get(index);
            child.setOutput("start", "item", item);
            child.setOutput("start", "index", index);
            engineProvider.getObject().runSubgraph(subGraph, child, env,
                    env.prefixed(node.id()) + "/");
            collected.add(collectItemOutput(outputSelector, subGraph, child, item));
        }
        ctx.setOutput(node.id(), "output", collected);
    }

    private Object collectItemOutput(String outputSelector, FlowGraph subGraph,
                                     WorkflowContext child, Object item) {
        String ref = outputSelector;
        if (ref == null || ref.isBlank()) {
            // 缺省：子图 answer→text，end→output，llm→text（按声明序取第一个命中类型）
            ref = subGraph.nodes().stream()
                    .filter(n -> "answer".equals(n.type()) || "end".equals(n.type()) || "llm".equals(n.type()))
                    .map(n -> n.id() + ("end".equals(n.type()) ? ".output" : ".text"))
                    .findFirst().orElse(null);
        }
        Object value = ref == null ? null : child.get(ref);
        return value != null ? value : VarRenderer.display(item);
    }

    private FlowGraph parseSubGraph(FlowNode node) {
        Map<String, Object> graphMap = node.map("graph");
        if (graphMap.isEmpty()) {
            throw new ServiceException(400, "节点「" + node.title() + "」未配置迭代子图");
        }
        return FlowGraph.fromMap(graphMap);
    }

    private List<Object> resolveItems(FlowNode node, WorkflowContext ctx) {
        Object source = VarRenderer.resolve(node.str("items", ""), ctx.resolver());
        if (source instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (source instanceof String text && !text.isBlank()) {
            var json = AiJson.tryParse(text);
            if (json != null && json.isArray()) {
                return AiJson.MAPPER.convertValue(json, List.class);
            }
        }
        if (source == null || VarRenderer.display(source).isBlank()) {
            return List.of();
        }
        return List.of(source);
    }
}
