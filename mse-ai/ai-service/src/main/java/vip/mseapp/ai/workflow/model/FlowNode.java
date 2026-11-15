package vip.mseapp.ai.workflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画布节点（mse-flow/v1 DSL）：type 决定执行器，data 为该类型的配置键值
 * （键约定见各 NodeExecutor，弱类型承载以便前端画布直接往返）。
 *
 * <p>访问器为 {@code id()} 风格（非 JavaBean getter），Jackson 2/3 均探测不到——
 * 序列化（DSL 导入返回 / JSON 往返）必须靠字段上的 {@code @JsonProperty} 显式暴露。
 */
public class FlowNode {

    @JsonProperty
    private String id;

    /** 节点类型：start/llm/knowledge/tool/dataset/condition/classifier/extractor/code/http/template/assign/aggregator/iteration/answer/end */
    @JsonProperty
    private String type;

    @JsonProperty
    private String title;

    @JsonProperty
    private Map<String, Object> data = new LinkedHashMap<>();

    @JsonProperty
    private Map<String, Object> position = new LinkedHashMap<>();

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String type() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String title() {
        return title == null || title.isBlank() ? type : title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Map<String, Object> data() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data == null ? new LinkedHashMap<>() : data;
    }

    public Map<String, Object> position() {
        return position;
    }

    public void setPosition(Map<String, Object> position) {
        this.position = position == null ? new LinkedHashMap<>() : position;
    }

    // ---------- data 的类型化读取（缺失/类型不符回退默认值） ----------

    public String str(String key, String def) {
        Object v = data.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public Long lng(String key, Long def) {
        Object v = data.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? def : Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public Integer intg(String key, Integer def) {
        Object v = data.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? def : Integer.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public Double dbl(String key, Double def) {
        Object v = data.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? def : Double.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean bool(String key, boolean def) {
        Object v = data.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object v = data.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(String key) {
        Object v = data.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listMap(String key) {
        Object v = data.get(key);
        if (!(v instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    public FlowNode copy() {
        FlowNode node = new FlowNode();
        node.setId(id);
        node.setType(type);
        node.setTitle(title);
        node.setData(new LinkedHashMap<>(data));
        node.setPosition(new LinkedHashMap<>(position));
        return node;
    }
}
