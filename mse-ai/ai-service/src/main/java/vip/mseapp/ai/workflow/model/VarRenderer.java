package vip.mseapp.ai.workflow.model;

import vip.mseapp.ai.support.AiJson;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变量模板渲染：{@code {{nodeId.varName}}} / {@code {{sys.query}}}。
 * Dify 的 {@code {{#id.var#}}} 语法在 DSL 导入时统一转为本语法。
 */
public final class VarRenderer {

    private static final Pattern REF = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_\\-.]+)\\s*\\}\\}");

    private VarRenderer() {
    }

    /** 提取模板中的变量引用（去重保序） */
    public static List<String> refs(String template) {
        Set<String> refs = new LinkedHashSet<>();
        if (template != null) {
            Matcher matcher = REF.matcher(template);
            while (matcher.find()) {
                refs.add(matcher.group(1));
            }
        }
        return new ArrayList<>(refs);
    }

    /** 模板是否为"单个变量引用构成的整串"（答案流式直通判定用） */
    public static boolean isPureRef(String template) {
        if (template == null) {
            return false;
        }
        List<String> refs = refs(template);
        if (refs.size() != 1) {
            return false;
        }
        Matcher matcher = REF.matcher(template.trim());
        return matcher.matches();
    }

    /** 文本解析：整体恰为一个引用时返回原始对象（条件判断保类型），否则渲染为字符串 */
    public static Object resolve(String text, Function<String, Object> resolver) {
        if (text != null && isPureRef(text.trim())) {
            return resolver.apply(refs(text.trim()).get(0));
        }
        return render(text, resolver);
    }

    /** 模板渲染：引用替换为展示串，未知引用渲染为空串 */
    public static String render(String template, Function<String, Object> resolver) {
        if (template == null || !template.contains("{{")) {
            return template;
        }
        Matcher matcher = REF.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = display(resolver.apply(matcher.group(1)));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 值展示：null→""，字符串原样，其余序列化 JSON */
    public static String display(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return AiJson.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
