package vip.mseapp.ai.workflow.model;

import tools.jackson.databind.JsonNode;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画布全局参数类型工具（mse-flow/v1 globals.type）：类型名归一 + 取值按类型转换。
 * 集合/对象在画布配置中以 JSON 文本填写，运行期转为 List/Map；数值整型优先。
 * 取值为 null 或空白文本时回退该类型空值（未填默认值不视为错误）。
 */
public final class VarTypes {

    /** 支持的参数类型（缺省 string，兼容旧图） */
    public static final Set<String> TYPES = Set.of("string", "number", "boolean", "array", "object");

    public static final String DEFAULT_TYPE = "string";

    private VarTypes() {
    }

    /** 类型名归一：空白/未知一律回退 string */
    public static String normalize(Object type) {
        String value = type == null ? "" : String.valueOf(type).trim().toLowerCase();
        return TYPES.contains(value) ? value : DEFAULT_TYPE;
    }

    /** 按类型转换取值；无法转换抛 400（errHint 携带节点/参数定位）；null/空白回退类型空值 */
    public static Object coerce(Object raw, String type, String errHint) {
        if (raw == null || (raw instanceof String text && text.isBlank())) {
            return emptyOf(type);
        }
        return switch (type) {
            case "number" -> toNumber(raw, errHint);
            case "boolean" -> toBoolean(raw, errHint);
            case "array" -> toArray(raw, errHint);
            case "object" -> toMap(raw, errHint);
            default -> raw == null ? "" : VarRenderer.display(raw);
        };
    }

    /** 类型空值（默认值未填 / reset 无声明默认值时的兜底） */
    public static Object emptyOf(String type) {
        return switch (type) {
            case "number" -> 0L;
            case "boolean" -> Boolean.FALSE;
            case "array" -> new ArrayList<>();
            case "object" -> new LinkedHashMap<>();
            default -> "";
        };
    }

    /** 数值化：整型保 Long、其余 Double；解析失败抛 400 */
    public static Number toNumber(Object raw, String errHint) {
        if (raw instanceof Number number) {
            return number instanceof Double || number instanceof Float
                    ? number.doubleValue() : number.longValue();
        }
        if (raw instanceof Boolean) {
            throw new ServiceException(400, errHint + "需要数值，实际为布尔值");
        }
        String text = VarRenderer.display(raw).trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new ServiceException(400, errHint + "不是合法数值: " + text);
            }
        }
    }

    /** 布尔化：Boolean 原样、字符串 true/false、数值非零为真；其余抛 400 */
    public static Boolean toBoolean(Object raw, String errHint) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.doubleValue() != 0d;
        }
        String text = VarRenderer.display(raw).trim().toLowerCase();
        if ("true".equals(text) || "false".equals(text)) {
            return Boolean.parseBoolean(text);
        }
        throw new ServiceException(400, errHint + "需要布尔值（true/false）: " + text);
    }

    /** 集合化：List 拷贝为可变 ArrayList、字符串按 JSON 数组解析；其余抛 400 */
    public static List<Object> toArray(Object raw, String errHint) {
        if (raw instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        JsonNode node = tryParseJson(raw);
        if (node != null && node.isArray()) {
            return AiJson.MAPPER.convertValue(node, List.class);
        }
        throw new ServiceException(400, errHint + "需要 JSON 数组（如 [1,2]）: " + VarRenderer.display(raw));
    }

    /** 对象化：Map 拷贝为有序 Map、字符串按 JSON 对象解析；其余抛 400 */
    public static Map<String, Object> toMap(Object raw, String errHint) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        JsonNode node = tryParseJson(raw);
        if (node != null && node.isObject()) {
            return AiJson.MAPPER.convertValue(node, Map.class);
        }
        throw new ServiceException(400, errHint + "需要 JSON 对象（如 {\"a\":1}）: " + VarRenderer.display(raw));
    }

    private static JsonNode tryParseJson(Object raw) {
        if (raw instanceof String text && !text.isBlank()) {
            return AiJson.tryParse(text);
        }
        return null;
    }
}
