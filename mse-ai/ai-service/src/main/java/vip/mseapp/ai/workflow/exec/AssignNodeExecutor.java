package vip.mseapp.ai.workflow.exec;

import org.springframework.stereotype.Component;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.VarTypes;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 变量赋值节点：执行期把「全局参数 → 变量模板」的取值按操作写回 {@code env.*}（画布全局参数），
 * 后续节点引用 {@code {{env.参数名}}} 即得新值。取值为纯单引用时保留原始类型（数值/对象/数组），
 * 混合文本按字符串渲染。
 *
 * <p>操作（assignments[].op，缺省 set 兼容旧图）：
 * set 覆盖 / reset 重置为声明默认值 / append 追加（字符串尾拼或集合尾插）/
 * add 累加、subtract 累减（数值）/ merge 合并（集合拼接、对象浅合并）。
 * 目标参数有声明类型时按类型转换取值；迭代子图内赋值经上下文父链透传，跨迭代累计生效。
 */
@Component
public class AssignNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "assign";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        List<Map<String, Object>> assignments = node.listMap("assignments");
        if (assignments.isEmpty()) {
            throw new ServiceException(400, "节点「" + node.title() + "」未配置赋值项");
        }
        List<String> assigned = new ArrayList<>();
        for (Map<String, Object> item : assignments) {
            String name = String.valueOf(item.getOrDefault("name", "")).trim();
            if (name.isBlank()) {
                continue;
            }
            String op = String.valueOf(item.getOrDefault("op", "set")).trim();
            Object operand = resolveOperand(item.get("value"), ctx);
            ctx.setOutput("env", name, apply(node, ctx, name, op, operand));
            assigned.add(name);
        }
        if (assigned.isEmpty()) {
            throw new ServiceException(400, "节点「" + node.title() + "」赋值项缺少变量名");
        }
        ctx.setOutput(node.id(), "assigned", String.join(", ", assigned));
    }

    /** 取值解析：字符串按模板渲染（纯单引用保留原始类型），DSL 直传的非字符串（数值/数组/对象）原样使用 */
    private Object resolveOperand(Object raw, WorkflowContext ctx) {
        if (raw instanceof String text) {
            return VarRenderer.resolve(text, ctx.resolver());
        }
        return raw;
    }

    private Object apply(FlowNode node, WorkflowContext ctx, String name, String op, Object operand) {
        String declared = ctx.envType(name);
        Object old = ctx.get("env." + name);
        String hint = "节点「" + node.title() + "」全局参数 " + name + " ";
        return switch (op) {
            case "set" -> declared == null ? operand : VarTypes.coerce(operand, declared, hint);
            case "reset" -> {
                Object declaredDefault = ctx.envDefault(name);
                yield declaredDefault != null
                        ? declaredDefault
                        : VarTypes.emptyOf(declared == null ? VarTypes.DEFAULT_TYPE : declared);
            }
            case "append" -> append(declared, old, operand);
            case "add" -> arithmetic(old, operand, true, hint);
            case "subtract" -> arithmetic(old, operand, false, hint);
            case "merge" -> merge(hint, old, operand);
            default -> throw new ServiceException(400, hint + "不支持的赋值操作: " + op);
        };
    }

    /** 追加：集合场景尾插（操作数为数组则整段拼接），否则字符串尾拼 */
    private Object append(String declared, Object old, Object operand) {
        boolean listMode = declared == null
                ? old instanceof List<?> || operand instanceof List<?>
                : "array".equals(declared);
        if (listMode) {
            List<Object> list = old instanceof List<?> existed ? new ArrayList<>(existed) : new ArrayList<>();
            Object addition = unwrapJson(operand);
            if (addition instanceof List<?> items) {
                list.addAll(items);
            } else if (operand != null) {
                list.add(addition);
            }
            return list;
        }
        return VarRenderer.display(old) + VarRenderer.display(operand);
    }

    /** 数值累加/累减：整型间保 Long，否则按 Double；无法数值化抛 400 */
    private Object arithmetic(Object old, Object operand, boolean add, String hint) {
        Number base = VarTypes.toNumber(old, hint);
        Number delta = VarTypes.toNumber(operand, hint);
        if (base instanceof Long && delta instanceof Long) {
            return add ? base.longValue() + delta.longValue() : base.longValue() - delta.longValue();
        }
        return add ? base.doubleValue() + delta.doubleValue() : base.doubleValue() - delta.doubleValue();
    }

    /** 合并：集合整段拼接、对象浅合并（后者键覆盖前者）；两者皆非抛 400 */
    private Object merge(String hint, Object old, Object operand) {
        Object addition = unwrapJson(operand);
        if (old instanceof List<?> || addition instanceof List<?>) {
            List<Object> list = old instanceof List<?> existed ? new ArrayList<>(existed) : new ArrayList<>();
            if (addition instanceof List<?> items) {
                list.addAll(items);
            } else if (addition != null) {
                list.add(addition);
            }
            return list;
        }
        if (old instanceof Map<?, ?> || addition instanceof Map<?, ?>) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (old instanceof Map<?, ?> existed) {
                existed.forEach((key, value) -> merged.put(String.valueOf(key), value));
            }
            if (addition instanceof Map<?, ?> items) {
                items.forEach((key, value) -> merged.put(String.valueOf(key), value));
            }
            return merged;
        }
        throw new ServiceException(400, hint + "合并操作仅支持集合或对象");
    }

    /** 手填 JSON 文本的操作数集合化（数组/对象字面量转 List/Map，其余原样），与 set 按声明类型转换的行为对齐 */
    private Object unwrapJson(Object operand) {
        if (!(operand instanceof String text) || text.isBlank()) {
            return operand;
        }
        var node = AiJson.tryParse(text);
        if (node == null) {
            return operand;
        }
        if (node.isArray()) {
            return AiJson.MAPPER.convertValue(node, List.class);
        }
        if (node.isObject()) {
            return AiJson.MAPPER.convertValue(node, Map.class);
        }
        return operand;
    }
}
