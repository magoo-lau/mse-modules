package vip.mseapp.ai.workflow.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 单次工作流执行的变量上下文：以 {@code nodeId.varName} 为键存各节点输出，
 * 系统变量（sys.*）在启动时预置；分支路由节点的出口选择单独登记。
 *
 * <p>并行分支下多个节点同时执行并写本上下文：集合全部为并发实现，
 * finalAnswer/finalOutput 的首写语义（先到先得）加锁保证原子。
 */
public class WorkflowContext {

    private final Map<String, Object> vars = new ConcurrentHashMap<>();

    /** 全局参数声明（画布 globals）：name → 声明类型，赋值节点按类型写回 */
    private final Map<String, String> envTypes = new ConcurrentHashMap<>();

    /** 全局参数声明：name → 默认值（reset 操作恢复用） */
    private final Map<String, Object> envDefaults = new ConcurrentHashMap<>();

    /** 迭代子图上下文的父级：env.* 写入沿父链透传（迭代内累加/追加跨迭代生效） */
    private volatile WorkflowContext envParent;

    private final Map<String, String> branches = new ConcurrentHashMap<>();

    private final List<NodeTrace> traces = new CopyOnWriteArrayList<>();

    /** 输出已流式直通给客户端的节点（Answer 纯引用该节点时不再重复下发） */
    private final Set<String> streamedNodes = ConcurrentHashMap.newKeySet();

    private String finalAnswer;

    private Object finalOutput;

    public void setOutput(String nodeId, String name, Object value) {
        if (name != null && !name.isBlank()) {
            vars.put(nodeId + "." + name, value);
            // 全局参数写入沿父链透传：迭代子图内对 env.* 的赋值对主流程与后续迭代可见
            if ("env".equals(nodeId) && envParent != null) {
                envParent.setOutput("env", name, value);
            }
        }
    }

    /** 登记全局参数声明（FlowRunner 启动预置时调用） */
    public void registerEnv(String name, String type, Object defaultValue) {
        envTypes.put(name, type);
        envDefaults.put(name, defaultValue);
    }

    /** 全局参数声明类型（未登记的全局参数名返回 null，赋值时按动态类型处理） */
    public String envType(String name) {
        return envTypes.get(name);
    }

    /** 全局参数默认值（未登记返回 null） */
    public Object envDefault(String name) {
        return envDefaults.get(name);
    }

    public void setOutputs(String nodeId, Map<String, Object> outputs) {
        if (outputs != null) {
            outputs.forEach((name, value) -> setOutput(nodeId, name, value));
        }
    }

    public Object get(String ref) {
        Object exact = vars.get(ref);
        if (exact != null) {
            return exact;
        }
        // 深层取值：vars 键固定为 nodeId.varName 两段，更多段视为对复合出参下钻
        // （如 http1.json.user.name → vars["http1.json"] 后逐段走 Map 键 / List 下标）
        String[] parts = ref.split("\\.");
        if (parts.length < 3) {
            return null;
        }
        Object current = vars.get(parts[0] + "." + parts[1]);
        for (int i = 2; i < parts.length && current != null; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else if (current instanceof List<?> list) {
                try {
                    current = list.get(Integer.parseInt(parts[i]));
                } catch (Exception e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    public String str(String ref) {
        return VarRenderer.display(get(ref));
    }

    /** 引用解析函数（VarRenderer/模板引擎共用） */
    public Function<String, Object> resolver() {
        return this::get;
    }

    public void setBranch(String nodeId, String handle) {
        branches.put(nodeId, handle);
    }

    public String getBranch(String nodeId) {
        return branches.get(nodeId);
    }

    /** 节点输出是否已确定（执行器据此实现幂等/跳过语义） */
    public boolean hasOutput(String ref) {
        return vars.containsKey(ref);
    }

    public void markStreamed(String nodeId) {
        streamedNodes.add(nodeId);
    }

    public boolean isStreamed(String nodeId) {
        return streamedNodes.contains(nodeId);
    }

    public List<NodeTrace> traces() {
        return traces;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public synchronized void setFinalAnswer(String finalAnswer) {
        if (this.finalAnswer == null || this.finalAnswer.isBlank()) {
            this.finalAnswer = finalAnswer;
        }
    }

    public Object getFinalOutput() {
        return finalOutput;
    }

    public synchronized void setFinalOutput(Object finalOutput) {
        if (this.finalOutput == null) {
            this.finalOutput = finalOutput;
        }
    }

    /** 扁平变量快照（调试面板/模板引擎用） */
    public Map<String, Object> snapshot() {
        return new LinkedHashMap<>(vars);
    }

    /** 继承父上下文全部变量与全局参数声明（迭代子图可见外层变量），并登记 env 写穿透的父级 */
    public void inherit(WorkflowContext parent) {
        vars.putAll(parent.snapshot());
        envTypes.putAll(parent.envTypes);
        envDefaults.putAll(parent.envDefaults);
        this.envParent = parent;
    }

    /** 嵌套变量视图（Jinja2 点号取值：{"llm1":{"text":...}}） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> nested() {
        Map<String, Object> root = new LinkedHashMap<>();
        vars.forEach((key, value) -> {
            String[] parts = key.split("\\.", 2);
            if (parts.length == 2) {
                Object child = root.computeIfAbsent(parts[0], k -> new LinkedHashMap<String, Object>());
                if (child instanceof Map<?, ?> map) {
                    ((Map<String, Object>) map).put(parts[1], value);
                }
            } else {
                root.put(key, value);
            }
        });
        return root;
    }
}
