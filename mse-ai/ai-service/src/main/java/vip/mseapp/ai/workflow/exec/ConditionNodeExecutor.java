package vip.mseapp.ai.workflow.exec;

import org.springframework.stereotype.Component;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 条件分支节点：用例按序求值，命中即路由 {@code case<i>}，全不命中走 {@code default}。
 * 用例 = 条件列表（and/or 组合），条件左值支持变量引用（整串单引用时保留原始类型比较）。
 */
@Component
public class ConditionNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "condition";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        List<Map<String, Object>> cases = node.listMap("cases");
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> item = cases.get(i);
            boolean and = !"or".equalsIgnoreCase(String.valueOf(item.getOrDefault("logic", "and")));
            List<Map<String, Object>> conditions = castMaps(item.get("conditions"));
            boolean matched = and;
            for (int c = 0; c < conditions.size(); c++) {
                boolean result = evaluate(conditions.get(c), ctx);
                if (c == 0) {
                    matched = result;
                } else {
                    matched = and ? (matched && result) : (matched || result);
                }
            }
            if (matched) {
                String handle = "case" + i;
                ctx.setBranch(node.id(), handle);
                ctx.setOutput(node.id(), "matched", String.valueOf(item.getOrDefault("id", handle)));
                return;
            }
        }
        ctx.setBranch(node.id(), "default");
        ctx.setOutput(node.id(), "matched", "default");
    }

    private boolean evaluate(Map<String, Object> condition, WorkflowContext ctx) {
        Object left = VarRenderer.resolve(String.valueOf(condition.getOrDefault("left", "")), ctx.resolver());
        String op = String.valueOf(condition.getOrDefault("op", "contains"));
        Object right = VarRenderer.resolve(String.valueOf(condition.getOrDefault("right", "")), ctx.resolver());
        return switch (op) {
            case "contains" -> display(left).contains(display(right));
            case "not_contains" -> !display(left).contains(display(right));
            case "starts_with" -> display(left).startsWith(display(right));
            case "ends_with" -> display(left).endsWith(display(right));
            case "eq" -> compareEquals(left, right);
            case "ne" -> !compareEquals(left, right);
            case "gt" -> compareNumber(left, right) > 0;
            case "ge" -> compareNumber(left, right) >= 0;
            case "lt" -> compareNumber(left, right) < 0;
            case "le" -> compareNumber(left, right) <= 0;
            case "empty" -> display(left).isBlank();
            case "not_empty" -> !display(left).isBlank();
            case "regex" -> Pattern.compile(display(right)).matcher(display(left)).find();
            default -> throw new ServiceException(400, "不支持的条件运算符: " + op);
        };
    }

    private static String display(Object value) {
        return VarRenderer.display(value);
    }

    private static boolean compareEquals(Object left, Object right) {
        if (left instanceof Number ln && right instanceof Number rn) {
            return ln.doubleValue() == rn.doubleValue();
        }
        return display(left).equals(display(right));
    }

    private static int compareNumber(Object left, Object right) {
        try {
            double l = Double.parseDouble(display(left));
            double r = Double.parseDouble(display(right));
            return Double.compare(l, r);
        } catch (NumberFormatException e) {
            return display(left).compareTo(display(right));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }
}
