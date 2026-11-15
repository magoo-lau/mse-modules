package vip.mseapp.ai.workflow.dsl;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import vip.mseapp.ai.workflow.model.FlowGraph;
import vip.mseapp.core.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自有 DSL（mse-flow/v1 YAML）：画布的导入导出格式，可读、可版本化、可跨环境迁移。
 */
public final class FlowDsl {

    private FlowDsl() {
    }

    public static String toYaml(FlowGraph graph, String name, String description) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", FlowGraph.DSL_VERSION);
        root.put("name", name);
        root.put("description", description);
        root.put("graph", graph.toMap());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(root);
    }

    @SuppressWarnings("unchecked")
    public static FlowGraph fromYaml(String yaml) {
        Object root;
        try {
            root = new Yaml().load(yaml);
        } catch (Exception e) {
            throw new ServiceException(400, "YAML 解析失败: " + e.getMessage());
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new ServiceException(400, "DSL 内容不是合法的映射结构");
        }
        String version = String.valueOf(((Map<String, Object>) map).getOrDefault("version", ""));
        if (!FlowGraph.DSL_VERSION.equals(version)) {
            throw new ServiceException(400, "不支持的 DSL 版本: " + version + "（期望 " + FlowGraph.DSL_VERSION + "）");
        }
        Object graph = ((Map<String, Object>) map).get("graph");
        if (!(graph instanceof Map)) {
            throw new ServiceException(400, "DSL 缺少 graph 段");
        }
        return FlowGraph.fromMap((Map<String, Object>) graph);
    }
}
