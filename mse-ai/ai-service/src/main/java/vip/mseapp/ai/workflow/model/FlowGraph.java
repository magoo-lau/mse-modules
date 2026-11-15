package vip.mseapp.ai.workflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.workflow.FlowValidator;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排图（mse-flow/v1）：nodes + edges + globals，附校验与 Map/YAML 互转。
 * JSON 存 ai_agent.graph_json。
 *
 * <p>访问器为 {@code nodes()} 风格，序列化依赖字段上的 {@code @JsonProperty}（见 FlowNode）。
 */
public class FlowGraph {

    public static final String DSL_VERSION = "mse-flow/v1";

    @JsonProperty
    private List<FlowNode> nodes = new ArrayList<>();

    @JsonProperty
    private List<FlowEdge> edges = new ArrayList<>();

    /** 全局参数（画布级）：运行入口预置为 {@code env.<name>}，赋值节点可在执行期改写 */
    @JsonProperty
    private List<Map<String, Object>> globals = new ArrayList<>();

    public List<FlowNode> nodes() {
        return nodes;
    }

    public void setNodes(List<FlowNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }

    public List<FlowEdge> edges() {
        return edges;
    }

    public void setEdges(List<FlowEdge> edges) {
        this.edges = edges == null ? new ArrayList<>() : edges;
    }

    public List<Map<String, Object>> globals() {
        return globals;
    }

    public void setGlobals(List<Map<String, Object>> globals) {
        this.globals = globals == null ? new ArrayList<>() : globals;
    }

    public FlowNode node(String id) {
        return nodes.stream().filter(n -> n.id() != null && n.id().equals(id)).findFirst().orElse(null);
    }

    /** 开始节点 id（约定唯一，校验保证） */
    public String startId() {
        return nodes.stream().filter(n -> "start".equals(n.type())).map(FlowNode::id).findFirst().orElse(null);
    }

    public List<FlowEdge> outEdges(String nodeId) {
        return edges.stream().filter(e -> nodeId.equals(e.source())).toList();
    }

    public List<FlowEdge> inEdges(String nodeId) {
        return edges.stream().filter(e -> nodeId.equals(e.target())).toList();
    }

    /** 解析 graph_json；空串返回 null（未编排），非法 JSON 抛 400 */
    public static FlowGraph parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return AiJson.MAPPER.readValue(json, FlowGraph.class);
        } catch (Exception e) {
            throw new ServiceException(400, "编排图 JSON 解析失败: " + e.getMessage());
        }
    }

    public String toJson() {
        return AiJson.toJson(this);
    }

    /** 校验错误清单（空列表 = 通过） */
    public List<String> validate() {
        return FlowValidator.validate(this);
    }

    /** 校验并抛错（保存/执行前统一入口） */
    public void requireValid() {
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            throw new ServiceException(400, "编排图不合法: " + String.join("; ", errors));
        }
    }

    /** 全量 Map 化（nodes/edges 展开为普通映射，供 YAML 导出等非 Jackson 通道使用） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", DSL_VERSION);
        map.put("nodes", nodes.stream()
                .map(node -> AiJson.MAPPER.convertValue(node, Map.class)).toList());
        map.put("edges", edges.stream()
                .map(edge -> AiJson.MAPPER.convertValue(edge, Map.class)).toList());
        map.put("globals", globals);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static FlowGraph fromMap(Map<String, Object> map) {
        FlowGraph graph = new FlowGraph();
        Object nodes = map.get("nodes");
        Object edges = map.get("edges");
        Object globals = map.get("globals");
        if (nodes instanceof List<?> nodeList) {
            for (Object item : nodeList) {
                if (item instanceof Map<?, ?> nodeMap) {
                    graph.nodes.add(AiJson.MAPPER.convertValue(nodeMap, FlowNode.class));
                }
            }
        }
        if (edges instanceof List<?> edgeList) {
            for (Object item : edgeList) {
                if (item instanceof Map<?, ?> edgeMap) {
                    graph.edges.add(AiJson.MAPPER.convertValue(edgeMap, FlowEdge.class));
                }
            }
        }
        if (globals instanceof List<?> globalList) {
            for (Object item : globalList) {
                if (item instanceof Map<?, ?> globalMap) {
                    graph.globals.add(new LinkedHashMap<>((Map<String, Object>) globalMap));
                }
            }
        }
        return graph;
    }
}
