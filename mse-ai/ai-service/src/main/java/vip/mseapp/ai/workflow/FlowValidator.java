package vip.mseapp.ai.workflow;

import vip.mseapp.ai.workflow.model.FlowEdge;
import vip.mseapp.ai.workflow.model.FlowGraph;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.VarTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编排图静态校验：结构完整性 / 可达性 / 无环 / 分支与变量引用合法性。
 * 保存与试运行前共用；错误以可读中文清单返回。
 */
public final class FlowValidator {

    /** 支持的节点类型（执行器注册表之外的类型拒绝保存） */
    public static final Set<String> NODE_TYPES = Set.of(
            "start", "llm", "knowledge", "tool", "dataset", "condition", "classifier",
            "extractor", "code", "http", "template", "aggregator", "assign", "iteration", "answer", "end");

    /** 赋值节点支持的操作（与 AssignNodeExecutor 对齐） */
    public static final Set<String> ASSIGN_OPS = Set.of("set", "reset", "append", "add", "subtract", "merge");

    /** 分支路由节点（连线必须携带 sourceHandle） */
    private static final Set<String> ROUTER_TYPES = Set.of("condition", "classifier");

    private FlowValidator() {
    }

    public static List<String> validate(FlowGraph graph) {
        List<String> errors = new ArrayList<>();
        if (graph.nodes().isEmpty()) {
            errors.add("画布为空");
            return errors;
        }

        // id 唯一 + 类型合法 + start 唯一
        Set<String> ids = new HashSet<>();
        int startCount = 0;
        for (FlowNode node : graph.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                errors.add("存在未命名节点");
                continue;
            }
            if (!ids.add(node.id())) {
                errors.add("节点 id 重复: " + node.id());
            }
            if (!NODE_TYPES.contains(node.type())) {
                errors.add("未知节点类型 " + node.type() + ": " + node.title());
            }
            if ("start".equals(node.type())) {
                startCount++;
            }
        }
        if (startCount != 1) {
            errors.add(startCount == 0 ? "缺少开始节点" : "开始节点只能有一个");
        }

        // 边端点存在 + 路由边必须带 sourceHandle + 普通边不得带
        for (FlowEdge edge : graph.edges()) {
            if (graph.node(edge.source()) == null || graph.node(edge.target()) == null) {
                errors.add("连线端点不存在: " + edge.source() + " -> " + edge.target());
                continue;
            }
            String sourceType = graph.node(edge.source()).type();
            if (ROUTER_TYPES.contains(sourceType) && (edge.sourceHandle() == null || edge.sourceHandle().isBlank())) {
                errors.add("分支节点「" + graph.node(edge.source()).title() + "」的连线缺少分支标识");
            }
            if (!ROUTER_TYPES.contains(sourceType) && edge.sourceHandle() != null && !edge.sourceHandle().isBlank()) {
                errors.add("非分支节点「" + graph.node(edge.source()).title() + "」的连线不应携带分支标识");
            }
        }

        // 全局参数：命名 / 唯一性 / 类型 / 默认值可按类型解析
        Set<String> globalNames = new HashSet<>();
        for (Map<String, Object> global : graph.globals()) {
            String name = String.valueOf(global.getOrDefault("name", "")).trim();
            if (name.isBlank()) {
                errors.add("存在未命名的全局参数");
                continue;
            }
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                errors.add("全局参数名不合法: " + name + "（字母开头，字母/数字/下划线）");
            }
            if (!globalNames.add(name)) {
                errors.add("全局参数名重复: " + name);
            }
            String type = VarTypes.normalize(global.get("type"));
            try {
                VarTypes.coerce(global.get("value"), type, "全局参数「" + name + "」默认值");
            } catch (Exception e) {
                errors.add(e.getMessage());
            }
        }

        // 赋值操作合法性
        for (FlowNode node : graph.nodes()) {
            if (!"assign".equals(node.type())) {
                continue;
            }
            for (Map<String, Object> item : node.listMap("assignments")) {
                Object op = item.get("op");
                if (op != null && !ASSIGN_OPS.contains(String.valueOf(op).trim())) {
                    errors.add("节点「" + node.title() + "」不支持的赋值操作: " + op);
                }
            }
        }

        // 无环（顶层图；迭代子图单独校验）——DFS 染色
        detectCycle(graph, errors);

        // start 可达性
        String startId = graph.startId();
        if (startId != null) {
            Set<String> reachable = new HashSet<>();
            collectReachable(graph, startId, reachable);
            for (FlowNode node : graph.nodes()) {
                if (!reachable.contains(node.id()) && !"start".equals(node.type())) {
                    errors.add("节点「" + node.title() + "」未与开始节点连通");
                }
            }
            boolean hasTerminal = graph.nodes().stream()
                    .filter(n -> reachable.contains(n.id()))
                    .anyMatch(n -> "answer".equals(n.type()) || "end".equals(n.type()));
            if (!hasTerminal) {
                errors.add("缺少「直接回复」或「结束」节点");
            }
        }

        // 变量引用合法性：data 内全部字符串（含 cases/assignments/headers/args 等嵌套结构）递归收集
        for (FlowNode node : graph.nodes()) {
            Set<String> refs = new LinkedHashSet<>();
            collectRefs(node.data(), refs);
            refs.forEach(ref -> checkRef(graph, node, ref, errors));
        }
        return errors;
    }

    /** 递归收集 data 结构中出现的变量引用（去重） */
    private static void collectRefs(Object value, Set<String> out) {
        if (value instanceof String text) {
            out.addAll(VarRenderer.refs(text));
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectRefs(item, out));
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collectRefs(item, out));
        }
    }

    private static void checkRef(FlowGraph graph, FlowNode node, String ref, List<String> errors) {
        if (ref.startsWith("sys.") || ref.startsWith("env.")) {
            return;
        }
        int split = ref.indexOf('.');
        String owner = split > 0 ? ref.substring(0, split) : ref;
        if (graph.node(owner) == null) {
            errors.add("节点「" + node.title() + "」引用了不存在的变量来源: {{" + ref + "}}");
        }
    }

    private static void detectCycle(FlowGraph graph, List<String> errors) {
        Map<String, Integer> color = new HashMap<>();
        for (FlowNode node : graph.nodes()) {
            if (color.getOrDefault(node.id(), 0) == 0 && hasCycle(graph, node.id(), color)) {
                errors.add("编排图存在环路（循环请使用「迭代」节点）");
                return;
            }
        }
    }

    private static boolean hasCycle(FlowGraph graph, String id, Map<String, Integer> color) {
        color.put(id, 1);
        for (FlowEdge edge : graph.outEdges(id)) {
            int c = color.getOrDefault(edge.target(), 0);
            if (c == 1) {
                return true;
            }
            if (c == 0 && hasCycle(graph, edge.target(), color)) {
                return true;
            }
        }
        color.put(id, 2);
        return false;
    }

    private static void collectReachable(FlowGraph graph, String id, Set<String> reachable) {
        if (!reachable.add(id)) {
            return;
        }
        graph.outEdges(id).forEach(edge -> collectReachable(graph, edge.target(), reachable));
    }
}
