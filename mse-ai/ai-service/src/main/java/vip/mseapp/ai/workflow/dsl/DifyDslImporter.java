package vip.mseapp.ai.workflow.dsl;

import org.yaml.snakeyaml.Yaml;
import vip.mseapp.ai.workflow.model.FlowEdge;
import vip.mseapp.ai.workflow.model.FlowGraph;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Dify DSL（YAML）尽力导入：节点/边/分支/变量引用的格式映射，
 * 不可映射项（模型名、Dify 知识库/工具提供方、Python 代码等）降级为待人工配置项并生成警告清单。
 *
 * <p>变量引用语法翻译：{@code {{#node.var#}}} → {@code {{node.var}}}（sys.* 天然兼容）。
 */
public final class DifyDslImporter {

    /** Dify 引用语法 {{#id.var#}} */
    private static final Pattern DIFY_REF = Pattern.compile("\\{\\{#\\s*([A-Za-z0-9_\\-.]+?)\\s*#\\}\\}");

    /** Dify 比较运算符 → 本引擎 op */
    private static final Map<String, String> OPS = Map.ofEntries(
            Map.entry("contains", "contains"),
            Map.entry("not contains", "not_contains"),
            Map.entry("start with", "starts_with"),
            Map.entry("end with", "ends_with"),
            Map.entry("is", "eq"),
            Map.entry("is not", "ne"),
            Map.entry("=", "eq"),
            Map.entry("≠", "ne"),
            Map.entry("empty", "empty"),
            Map.entry("not empty", "not_empty"),
            Map.entry("exists", "not_empty"),
            Map.entry("not exists", "empty"),
            Map.entry(">", "gt"),
            Map.entry("≥", "ge"),
            Map.entry("<", "lt"),
            Map.entry("≤", "le"),
            Map.entry("before", "lt"),
            Map.entry("after", "gt"));

    private DifyDslImporter() {
    }

    public record ImportResult(FlowGraph graph, List<String> warnings) {
    }

    public static ImportResult importDsl(String yaml) {
        Object root;
        try {
            root = new Yaml().load(yaml);
        } catch (Exception e) {
            throw new ServiceException(400, "YAML 解析失败: " + e.getMessage());
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new ServiceException(400, "Dify DSL 内容不是合法的映射结构");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) ((Map<String, Object>) rootMap).get("workflow");
        if (workflow == null || !(workflow.get("graph") instanceof Map)) {
            throw new ServiceException(400, "不是 Dify DSL（缺少 workflow.graph 段）");
        }
        List<String> warnings = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> graphMap = (Map<String, Object>) workflow.get("graph");
        List<Map<String, Object>> rawNodes = castList(graphMap.get("nodes"));
        List<Map<String, Object>> rawEdges = castList(graphMap.get("edges"));

        FlowGraph graph = new FlowGraph();
        Map<String, String> caseHandleMap = new LinkedHashMap<>();
        Map<String, FlowNode> mapped = new LinkedHashMap<>();

        // 先收集 if-else/classifier 的 case_id → case{i} 映射，再统一转节点与边
        for (Map<String, Object> raw : rawNodes) {
            String id = str(raw.get("id"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) raw.getOrDefault("data", new LinkedHashMap<>());
            String type = str(data.get("type"));
            if ("if-else".equals(type) || "question-classifier".equals(type)) {
                List<String> ids = new ArrayList<>();
                if ("if-else".equals(type)) {
                    for (Map<String, Object> item : castList(data.get("cases"))) {
                        ids.add(str(item.get("case_id")));
                    }
                } else {
                    for (Map<String, Object> item : castList(data.get("classes"))) {
                        ids.add(str(item.get("id")));
                    }
                }
                for (int i = 0; i < ids.size(); i++) {
                    if (!ids.get(i).isEmpty()) {
                        caseHandleMap.put(id + "/" + ids.get(i), "case" + i);
                    }
                }
            }
        }

        for (Map<String, Object> raw : rawNodes) {
            FlowNode node = mapNode(raw, graphMap, caseHandleMap, warnings);
            if (node != null) {
                graph.nodes().add(node);
                mapped.put(node.id(), node);
            }
        }
        for (Map<String, Object> raw : rawEdges) {
            FlowEdge edge = mapEdge(raw, caseHandleMap, warnings);
            if (edge != null && mapped.containsKey(edge.source()) && mapped.containsKey(edge.target())) {
                graph.edges().add(edge);
            }
        }
        if (warnings.isEmpty()) {
            warnings.add("导入完成：请检查各节点的模型/工具/知识库配置是否需要人工补齐");
        }
        return new ImportResult(graph, warnings);
    }

    // ---------- 节点映射 ----------

    private static FlowNode mapNode(Map<String, Object> raw, Map<String, Object> graphMap,
                                    Map<String, String> caseHandleMap, List<String> warnings) {
        String id = str(raw.get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) raw.getOrDefault("data", new LinkedHashMap<>());
        String type = str(data.get("type"));
        String title = str(data.getOrDefault("title", type));
        if (raw.get("parentId") != null && !str(raw.get("parentId")).isEmpty()) {
            // 迭代子节点由 iteration 节点统一处理
            return null;
        }
        FlowNode node = new FlowNode();
        node.setId(id);
        node.setTitle(title);
        node.setPosition(position(raw));
        Map<String, Object> out = node.data();

        switch (type) {
            case "start" -> {
                node.setType("start");
                List<Map<String, Object>> variables = new ArrayList<>();
                for (Map<String, Object> item : castList(data.get("variables"))) {
                    variables.add(Map.of("name", str(item.get("variable")),
                            "required", Boolean.TRUE.equals(item.get("required"))));
                }
                out.put("variables", variables);
            }
            case "llm", "direct-answer", "answer" -> {
                if ("answer".equals(type) || "direct-answer".equals(type)) {
                    node.setType("answer");
                    out.put("content", translateRefs(str(data.get("answer"))));
                    return node;
                }
                node.setType("llm");
                List<Map<String, Object>> promptTemplate = castList(data.get("prompt_template"));
                StringBuilder system = new StringBuilder();
                StringBuilder user = new StringBuilder();
                for (Map<String, Object> item : promptTemplate) {
                    String role = str(item.get("role"));
                    String text = translateRefs(str(item.get("text")));
                    if ("system".equals(role)) {
                        system.append(system.isEmpty() ? "" : "\n").append(text);
                    } else if ("user".equals(role)) {
                        user.append(user.isEmpty() ? "" : "\n").append(text);
                    }
                }
                if (!system.isEmpty()) {
                    out.put("systemPrompt", system.toString());
                }
                out.put("prompt", user.isEmpty() ? "{{sys.query}}" : user.toString());
                Map<String, Object> memory = castMap(data.get("memory"));
                if (!memory.isEmpty()) {
                    out.put("memoryEnabled", true);
                    out.put("windowSize", castMap(memory.get("window")).get("size"));
                }
                warnModel(data, title, warnings);
            }
            case "knowledge-retrieval" -> {
                node.setType("knowledge");
                out.put("query", selectorRef(data.get("query_variable_selector"), "{{sys.query}}"));
                List<String> kbIds = new ArrayList<>();
                for (Object item : castList(data.get("dataset_ids"))) {
                    kbIds.add(str(item));
                }
                if (!kbIds.isEmpty()) {
                    warnings.add("节点「" + title + "」：Dify 知识库 ID (" + kbIds + ") 无法映射，请改绑本平台知识库");
                }
                out.put("kbIds", "");
                Map<String, Object> retrieval = castMap(data.get("multiple_retrieval_config"));
                if (!retrieval.isEmpty()) {
                    out.put("topK", retrieval.getOrDefault("top_k", 4));
                    Object score = retrieval.get("score_threshold");
                    if (score != null) {
                        out.put("minScore", score);
                    }
                }
            }
            case "tool" -> {
                node.setType("tool");
                out.put("toolCode", "");
                warnings.add("节点「" + title + "」：Dify 工具提供方无法映射，请改绑本平台工具中心工具");
            }
            case "if-else" -> {
                node.setType("condition");
                List<Map<String, Object>> cases = new ArrayList<>();
                for (Map<String, Object> item : castList(data.get("cases"))) {
                    List<Map<String, Object>> conditions = new ArrayList<>();
                    for (Map<String, Object> condition : castList(item.get("conditions"))) {
                        String rawOp = str(condition.get("comparison_operator"));
                        String op;
                        if ("in".equals(rawOp) || "not in".equals(rawOp)) {
                            op = "in".equals(rawOp) ? "contains" : "not_contains";
                            warnings.add("节点「" + title + "」：运算符 " + rawOp
                                    + "（成员判断）已按子串包含处理，请核对语义");
                        } else if (OPS.containsKey(rawOp)) {
                            op = OPS.get(rawOp);
                        } else {
                            op = "eq";
                            warnings.add("节点「" + title + "」：运算符 " + condition.get("comparison_operator")
                                    + " 暂不支持，已按 equals 处理");
                        }
                        Map<String, Object> mapped2 = new LinkedHashMap<>();
                        mapped2.put("left", selectorRef(condition.get("variable_selector"), "{{sys.query}}"));
                        mapped2.put("op", op);
                        Object value = condition.get("value");
                        mapped2.put("right", value == null ? "" : String.valueOf(value));
                        conditions.add(mapped2);
                    }
                    Map<String, Object> mappedCase = new LinkedHashMap<>();
                    mappedCase.put("logic", str(item.getOrDefault("logical_operator", "and")));
                    mappedCase.put("conditions", conditions);
                    cases.add(mappedCase);
                }
                out.put("cases", cases);
            }
            case "question-classifier" -> {
                node.setType("classifier");
                out.put("query", selectorRef(data.get("query_variable_selector"), "{{sys.query}}"));
                List<Map<String, Object>> classes = new ArrayList<>();
                for (Map<String, Object> item : castList(data.get("classes"))) {
                    Map<String, Object> mapped2 = new LinkedHashMap<>();
                    mapped2.put("name", str(item.get("name")));
                    mapped2.put("description", "");
                    classes.add(mapped2);
                }
                out.put("classes", classes);
                warnModel(data, title, warnings);
            }
            case "parameter-extractor" -> {
                node.setType("extractor");
                out.put("query", selectorRef(data.get("query_variable_selector"), "{{sys.query}}"));
                List<Map<String, Object>> fields = data.get("variables") != null
                        ? castList(data.get("variables")) : castList(data.get("extraction_variables"));
                List<Map<String, Object>> mappedFields = new ArrayList<>();
                for (Map<String, Object> item : fields) {
                    Map<String, Object> mapped2 = new LinkedHashMap<>();
                    mapped2.put("name", str(item.get("variable")));
                    mapped2.put("type", str(item.getOrDefault("type", "string")));
                    mapped2.put("description", str(item.get("description")));
                    mappedFields.add(mapped2);
                }
                out.put("fields", mappedFields);
                warnModel(data, title, warnings);
            }
            case "code" -> {
                node.setType("code");
                String language = str(data.getOrDefault("code_language", "javascript"));
                if (!"javascript".equals(language)) {
                    warnings.add("节点「" + title + "」：代码为 " + language
                            + "，本平台仅支持 JavaScript，请改写");
                }
                out.put("code", str(data.get("code")));
                Map<String, Object> args = new LinkedHashMap<>();
                for (Map<String, Object> item : castList(data.get("variables"))) {
                    args.put(str(item.get("variable")), selectorRef(item.get("value_selector"), ""));
                }
                out.put("args", args);
            }
            case "http-request" -> {
                node.setType("http");
                out.put("method", str(data.getOrDefault("method", "get")).toUpperCase());
                out.put("url", translateRefs(str(data.get("url"))));
                out.put("headers", parseHeaderLines(str(data.get("headers"))));
                Object body = data.get("body");
                if (body instanceof Map<?, ?> bodyMap) {
                    out.put("body", translateRefs(str(((Map<?, ?>) bodyMap).get("data"))));
                } else if (body != null) {
                    out.put("body", translateRefs(String.valueOf(body)));
                }
                if (data.get("authorization") instanceof Map<?, ?> auth
                        && !"no-auth".equals(str(((Map<?, ?>) auth).get("type")))) {
                    warnings.add("节点「" + title + "」：HTTP 鉴权配置请改用 headers 变量配置");
                }
            }
            case "template-transform" -> {
                node.setType("template");
                String template = translateRefs(str(data.get("template")));
                Map<String, Object> variables = castList(data.get("variables")).isEmpty()
                        ? Map.of() : new LinkedHashMap<>();
                for (Map<String, Object> item : castList(data.get("variables"))) {
                    String alias = str(item.get("variable"));
                    String ref = selectorRef(item.get("value_selector"), "");
                    // 模板里的别名引用改写为实际变量路径
                    template = replaceAlias(template, alias, ref);
                }
                out.put("template", template);
                out.put("variables", variables);
            }
            case "variable-aggregator" -> {
                node.setType("aggregator");
                List<Object> variables = new ArrayList<>();
                for (Object item : castList(data.get("variables"))) {
                    if (item instanceof List<?> selector && selector.size() >= 2) {
                        variables.add(selector.get(0) + "." + selector.get(1));
                    }
                }
                out.put("variables", variables);
            }
            case "iteration" -> mapIteration(node, raw, data, graphMap, caseHandleMap, warnings);
            case "end" -> {
                node.setType("end");
                Map<String, Object> outputs = new LinkedHashMap<>();
                for (Map<String, Object> item : castList(data.get("outputs"))) {
                    outputs.put(str(item.get("variable")), selectorRef(item.get("value_selector"), ""));
                }
                out.put("outputs", outputs);
            }
            default -> {
                warnings.add("跳过不支持的 Dify 节点类型 " + type + ": " + title);
                return null;
            }
        }
        return node;
    }

    /** 迭代节点：parentId 指向本节点的子节点组装为内嵌子图，迭代入口边替换为子图 start */
    private static void mapIteration(FlowNode node, Map<String, Object> raw, Map<String, Object> data,
                                     Map<String, Object> graphMap, Map<String, String> caseHandleMap,
                                     List<String> warnings) {
        String id = node.id();
        node.setType("iteration");
        FlowGraph subGraph = new FlowGraph();
        FlowNode innerStart = new FlowNode();
        innerStart.setId("start");
        innerStart.setType("start");
        innerStart.setTitle("迭代项");
        subGraph.nodes().add(innerStart);
        List<Map<String, Object>> allNodes = castList(graphMap.get("nodes"));
        List<Map<String, Object>> allEdges = castList(graphMap.get("edges"));
        Map<String, Boolean> childIds = new LinkedHashMap<>();
        for (Map<String, Object> candidate : allNodes) {
            if (id.equals(str(candidate.get("parentId")))) {
                childIds.put(str(candidate.get("id")), true);
            }
        }
        for (Map<String, Object> candidate : allNodes) {
            if (childIds.containsKey(str(candidate.get("id")))) {
                FlowNode child = mapNode(candidate, graphMap, caseHandleMap, warnings);
                if (child != null) {
                    subGraph.nodes().add(child);
                }
            }
        }
        String startNodeId = str(data.get("start_node_id"));
        for (Map<String, Object> edge : allEdges) {
            String source = str(edge.get("source"));
            String target = str(edge.get("target"));
            if (childIds.containsKey(source) && childIds.containsKey(target)) {
                subGraph.edges().add(new FlowEdge(source, target, handleOf(edge, caseHandleMap)));
            } else if (id.equals(source) && target.equals(startNodeId)) {
                subGraph.edges().add(new FlowEdge("start", target, null));
            }
        }
        node.data().put("graph", subGraph.toMap());
        node.data().put("items", selectorRef(data.get("iterator_selector"), "{{sys.query}}"));
        if (data.get("output_selector") instanceof List<?> outer && !outer.isEmpty()
                && outer.get(0) instanceof List<?> selector && selector.size() >= 2) {
            node.data().put("outputSelector", selector.get(0) + "." + selector.get(1));
        }
        warnings.add("节点「" + node.title() + "」：迭代子图已导入，请在画布中打开检查连线与配置");
    }

    // ---------- 边映射 ----------

    private static FlowEdge mapEdge(Map<String, Object> raw, Map<String, String> caseHandleMap,
                                    List<String> warnings) {
        String source = str(raw.get("source"));
        String target = str(raw.get("target"));
        String handle = handleOf(raw, caseHandleMap);
        return new FlowEdge(source, target, handle);
    }

    private static String handleOf(Map<String, Object> edge, Map<String, String> caseHandleMap) {
        String rawHandle = str(edge.get("sourceHandle"));
        if (rawHandle.isEmpty() || "source".equals(rawHandle)) {
            return null;
        }
        String key = str(edge.get("source")) + "/" + rawHandle;
        if (caseHandleMap.containsKey(key)) {
            return caseHandleMap.get(key);
        }
        if ("true".equals(rawHandle)) {
            return "case0";
        }
        if ("false".equals(rawHandle)) {
            return "default";
        }
        return rawHandle;
    }

    // ---------- 工具方法 ----------

    private static void warnModel(Map<String, Object> data, String title, List<String> warnings) {
        if (data.get("model") instanceof Map<?, ?> model && !str(((Map<?, ?>) model).get("name")).isEmpty()) {
            warnings.add("节点「" + title + "」：模型 " + str(((Map<?, ?>) model).get("name"))
                    + " 无法映射，请在本平台模型中心选择对应模型");
        }
    }

    /** Dify 变量选择器 [nodeId, var] → 引用模板 */
    private static String selectorRef(Object selector, String fallback) {
        if (selector instanceof List<?> list && list.size() >= 2) {
            return list.get(0) + "." + list.get(1);
        }
        return fallback;
    }

    private static String translateRefs(String text) {
        if (text == null) {
            return "";
        }
        java.util.regex.Matcher matcher = DIFY_REF.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, java.util.regex.Matcher
                    .quoteReplacement("{{" + matcher.group(1) + "}}"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String replaceAlias(String template, String alias, String ref) {
        if (alias.isEmpty() || ref.isEmpty()) {
            return template;
        }
        return template.replaceAll("\\{\\{\\s*" + Pattern.quote(alias) + "\\b", "{{" + ref);
    }

    /** "K1: V1\nK2: V2" 头串解析为 map */
    private static Map<String, Object> parseHeaderLines(String lines) {
        Map<String, Object> headers = new LinkedHashMap<>();
        for (String line : lines.split("\n")) {
            int split = line.indexOf(':');
            if (split > 0) {
                headers.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
            }
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> position(Map<String, Object> raw) {
        Object position = raw.get("position");
        if (position instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
