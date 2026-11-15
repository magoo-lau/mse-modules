package vip.mseapp.search.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.mseapp.search.api.SearchFieldDescriptor;
import vip.mseapp.search.config.SearchProperties;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.service.SearchFieldConfigService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 索引管理：按「租户 × 源」推导索引名（indexPrefix + "-" + sourceCode 小写 + "-" + tenantId）
 * 并保证索引存在（基础字段 mapping + 本租户字段配置生成的 mapping + 可配中文分析器）。
 *
 * <p>目录真租户化后每租户 × 每源一个索引：mapping/字段类型/新增字段完全租户自治，重建与清理
 * 互不影响；跨租户隔离由索引物理分桶 + 文档 tenantId 字段双保险。
 *
 * <p>字段 mapping 的增量演进：已存在的索引经 putMapping 合并新增字段（幂等，按 fieldsJson
 * 原文比对跳过重复推送）；已有字段类型变更会被引擎拒绝——此时告警提示走全量重建
 * （删索引后按新 mapping 重建）。
 */
@Component
public class EsIndexManager {

    private static final Logger log = LoggerFactory.getLogger(EsIndexManager.class);

    private final SearchEngineClient engineClient;
    private final SearchProperties properties;
    private final SearchFieldConfigService fieldConfigService;

    /** 已应用到索引的字段配置（indexName → fieldsJson 原文；原文未变不重复 putMapping） */
    private final ConcurrentHashMap<String, String> appliedFields = new ConcurrentHashMap<>();

    public EsIndexManager(SearchEngineClient engineClient, SearchProperties properties,
                          SearchFieldConfigService fieldConfigService) {
        this.engineClient = engineClient;
        this.properties = properties;
        this.fieldConfigService = fieldConfigService;
    }

    /**
     * 索引名：前缀 + 源编码 + 租户段（ES 索引名不允许大写；':' 为跨集群语法保留字符，
     * 替换为 '_'；tenantId 空=平台 0）。
     */
    public String indexName(String sourceCode, Long tenantId) {
        return properties.getIndexPrefix().toLowerCase() + "-"
                + sourceCode.toLowerCase().replace(':', '_') + "-" + (tenantId == null ? 0L : tenantId);
    }

    /** 保证「租户 × 源」索引存在且字段 mapping 就绪（幂等；每次写入链路调用，配置未变时零引擎请求） */
    public void ensureIndex(SearchSource source, Long tenantId) {
        String indexName = indexName(source.getSourceCode(), tenantId);
        // mapping = 本租户字段配置 ∪ 本租户新增字段（调用方处于归属租户上下文，行级隔离自动过滤）
        SearchFieldConfigService.MappingFields mapping = fieldConfigService.mappingFields(source);
        List<SearchFieldDescriptor> fields = mapping.fields();
        engineClient.ensureIndex(indexName, settings(), mappings(fields));
        if (fields.isEmpty() || mapping.fingerprint().equals(appliedFields.get(indexName))) {
            return;
        }
        try {
            engineClient.putMapping(indexName, mappings(fields));
            appliedFields.put(indexName, mapping.fingerprint());
        } catch (Exception e) {
            // 类型变更等引擎拒绝：缓存本次指纹避免每批重试刷日志，存量数据需全量重建
            appliedFields.put(indexName, mapping.fingerprint());
            log.warn("[mse-search] 源 {} 租户 {} 字段 mapping 增量更新失败（字段类型变更需全量重建）: {}",
                    source.getSourceCode(), tenantId, e.getMessage());
        }
    }

    /** 全量重建删索引后清除应用缓存（新索引按当前配置创建） */
    public void onIndexRemoved(String sourceCode, Long tenantId) {
        appliedFields.remove(indexName(sourceCode, tenantId));
    }

    private Map<String, Object> settings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);
        return settings;
    }

    /** mapping：基础字段 + 本租户字段配置（按 type 映射；配置为空时与历史统一 schema 完全一致） */
    private Map<String, Object> mappings(List<SearchFieldDescriptor> fields) {
        String analyzer = properties.getAnalyzer();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", textProperty(analyzer));
        props.put("content", textProperty(analyzer));
        props.put("extra", Map.of("type", "flattened"));
        props.put("tenantId", Map.of("type", "keyword"));
        props.put("sourceCode", Map.of("type", "keyword"));
        props.put("bizId", Map.of("type", "keyword"));
        props.put("permissionCode", Map.of("type", "keyword"));
        // 个人数据过滤字段（与 SearchDocDTO/SearchIndexPayload 对齐）
        props.put("ownerUserId", Map.of("type", "keyword"));
        props.put("deptId", Map.of("type", "keyword"));
        props.put("visibleType", Map.of("type", "keyword"));
        props.put("visibleUserIds", Map.of("type", "keyword"));
        props.put("updateTime", Map.of("type", "date", "format", "strict_date_optional_time||epoch_millis"));
        for (SearchFieldDescriptor field : fields) {
            props.put(field.getField(), fieldProperty(field, analyzer));
        }
        Map<String, Object> mappings = new LinkedHashMap<>();
        mappings.put("properties", props);
        return mappings;
    }

    /** 配置字段 → 引擎属性映射 */
    private Map<String, Object> fieldProperty(SearchFieldDescriptor field, String analyzer) {
        return switch (field.getType() == null ? SearchFieldDescriptor.TYPE_KEYWORD : field.getType()) {
            case SearchFieldDescriptor.TYPE_TEXT -> textProperty(analyzer);
            case SearchFieldDescriptor.TYPE_NUMBER -> Map.of("type", "double");
            case SearchFieldDescriptor.TYPE_DATE ->
                    Map.of("type", "date", "format", "strict_date_optional_time||epoch_millis");
            default -> Map.of("type", "keyword");
        };
    }

    private static Map<String, Object> textProperty(String analyzer) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "text");
        if (analyzer != null && !analyzer.isBlank() && !"standard".equalsIgnoreCase(analyzer)) {
            field.put("analyzer", analyzer);
        }
        field.put("fields", Map.of("keyword", Map.of("type", "keyword", "ignore_above", 256)));
        return field;
    }
}
