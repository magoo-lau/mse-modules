package vip.mseapp.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.search.api.SearchFieldDescriptor;
import vip.mseapp.search.config.SearchProperties;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.entity.SearchTenantField;
import vip.mseapp.search.mapper.SearchTenantFieldMapper;
import vip.mseapp.search.support.SearchJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 源字段配置服务：源字段配置（search_source.fields_json，随租户目录播种）与租户覆盖/新增
 * （search_tenant_field）的解析、校验与合并。
 *
 * <p>三级模型：业务 Contributor 编码声明默认（随推送刷新，管理端定制后不覆盖）→ 源配置
 * （驱动本租户索引 mapping 与索引时 extra 键提升）→ 租户覆盖（仅查询层：可搜/权重/高亮/展示/
 * 显示名，不能改类型）与租户新增（从业务字段目录挑选未收录字段、携带类型进入本租户索引 mapping）。
 * 目录真租户化后每租户 × 每源一个索引，mapping 完全租户自治。
 *
 * <p>安全底线：基础字段保留集（tenantId/permissionCode/visibleUserIds 等过滤字段）任何配置路径
 * 均不可占用——字段被重映射将破坏查询侧强制注入的三层过滤。
 */
@Service
public class SearchFieldConfigService {

    private static final Logger log = LoggerFactory.getLogger(SearchFieldConfigService.class);

    /** 字段名规则（与供数端 extra 键发射规则一致） */
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z0-9_]+");

    /** 系统保留字段（基础 schema + 三层过滤字段），平台与租户配置均不可新增/覆盖同名映射字段 */
    public static final Set<String> BASE_FIELDS = Set.of("title", "content", "extra", "tenantId",
            "sourceCode", "bizId", "permissionCode", "ownerUserId", "deptId",
            "visibleType", "visibleUserIds", "updateTime");

    private static final Set<String> TYPES =
            Set.of(SearchFieldDescriptor.TYPE_KEYWORD, SearchFieldDescriptor.TYPE_TEXT,
                    SearchFieldDescriptor.TYPE_NUMBER, SearchFieldDescriptor.TYPE_DATE);

    /** mapping 并集缓存 TTL（多实例部署下租户新增字段的最终一致窗口） */
    private static final long UNION_TTL_MILLIS = 60_000L;

    private final SearchTenantFieldMapper tenantFieldMapper;
    private final SearchProperties properties;

    /** sourceCode → 并集缓存（租户保存/恢复时主动失效，TTL 兜底） */
    private final Map<String, CachedUnion> mappingUnionCache = new ConcurrentHashMap<>();

    public SearchFieldConfigService(SearchTenantFieldMapper tenantFieldMapper, SearchProperties properties) {
        this.tenantFieldMapper = tenantFieldMapper;
        this.properties = properties;
    }

    // ==================== 平台配置 ====================

    /** 解析平台字段配置（空 = 无配置，查询退回内置 title/content 行为） */
    public List<SearchFieldDescriptor> parsePlatform(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isBlank()) {
            return List.of();
        }
        return normalize(SearchJson.parseList(fieldsJson, SearchFieldDescriptor.class));
    }

    /** 校验并序列化平台字段配置（管理端保存入口；新增/修改类型均合法，重建提示由调用方给出） */
    public String serializePlatform(List<SearchFieldDescriptor> fields) {
        return SearchJson.toJson(normalize(fields == null ? List.of() : fields));
    }

    /** 去重、补默认值与合法性校验 */
    private List<SearchFieldDescriptor> normalize(List<SearchFieldDescriptor> fields) {
        if (fields.size() > maxPerSource()) {
            throw new ServiceException(400, "单源字段数不能超过 " + maxPerSource());
        }
        Map<String, SearchFieldDescriptor> byField = new LinkedHashMap<>();
        for (SearchFieldDescriptor field : fields) {
            if (field.getField() == null || !FIELD_NAME.matcher(field.getField()).matches()) {
                throw new ServiceException(400, "字段名非法（须为 [A-Za-z0-9_]）: " + field.getField());
            }
            if (BASE_FIELDS.contains(field.getField())) {
                throw new ServiceException(400, "字段 " + field.getField() + " 为系统保留字段，不可配置");
            }
            if (byField.containsKey(field.getField())) {
                throw new ServiceException(400, "字段重复: " + field.getField());
            }
            String type = field.getType() == null || field.getType().isBlank()
                    ? SearchFieldDescriptor.TYPE_KEYWORD : field.getType();
            if (!TYPES.contains(type)) {
                throw new ServiceException(400, "字段 " + field.getField() + " 类型非法: " + type);
            }
            field.setType(type);
            if (field.getLabel() == null || field.getLabel().isBlank()) {
                field.setLabel(field.getField());
            }
            if (field.getWeight() != null && field.getWeight() <= 0) {
                throw new ServiceException(400, "字段 " + field.getField() + " 权重须大于 0");
            }
            byField.put(field.getField(), field);
        }
        return new ArrayList<>(byField.values());
    }

    // ==================== 租户覆盖/新增 ====================

    /** 当前租户对指定源集合的覆盖（无覆盖的源不在返回 Map 中；租户隔离由插件自动注入） */
    public Map<String, List<SearchFieldDescriptor>> loadTenantOverrides(List<String> sourceCodes) {
        if (sourceCodes == null || sourceCodes.isEmpty()) {
            return Map.of();
        }
        List<SearchTenantField> rows = tenantFieldMapper.selectList(new LambdaQueryWrapper<SearchTenantField>()
                .in(SearchTenantField::getSourceCode, sourceCodes));
        Map<String, List<SearchFieldDescriptor>> result = new LinkedHashMap<>();
        for (SearchTenantField row : rows) {
            if (row.getFieldsJson() != null && !row.getFieldsJson().isBlank()) {
                result.put(row.getSourceCode(), SearchJson.parseList(row.getFieldsJson(), SearchFieldDescriptor.class));
            }
        }
        return result;
    }

    /**
     * 校验并序列化租户覆盖/新增：平台已声明字段走覆盖语义（类型不可覆盖，剥离存储）；
     * 平台未声明字段为租户新增——必须携带合法类型（进入 mapping 并集的依据）、不得触碰保留集、
     * 数量受 tenant-max-added 限制。字段是否真实存在于业务目录由调用方校验（本方法只做结构校验）。
     */
    public String serializeTenantOverride(List<SearchFieldDescriptor> platform, List<SearchFieldDescriptor> overrides) {
        Set<String> platformFields = platform.stream().map(SearchFieldDescriptor::getField)
                .collect(Collectors.toSet());
        Map<String, SearchFieldDescriptor> cleaned = new LinkedHashMap<>();
        int addedCount = 0;
        for (SearchFieldDescriptor override : overrides == null ? List.<SearchFieldDescriptor>of() : overrides) {
            if (override.getField() == null || override.getField().isBlank()) {
                continue;
            }
            if (!FIELD_NAME.matcher(override.getField()).matches()) {
                throw new ServiceException(400, "字段名非法（须为 [A-Za-z0-9_]）: " + override.getField());
            }
            if (BASE_FIELDS.contains(override.getField())) {
                throw new ServiceException(400, "字段 " + override.getField() + " 为系统保留字段，不可配置");
            }
            if (cleaned.containsKey(override.getField())) {
                throw new ServiceException(400, "覆盖字段重复: " + override.getField());
            }
            if (platformFields.contains(override.getField())) {
                // 类型属平台 mapping 资产，覆盖项不携带
                override.setType(null);
            } else {
                // 租户新增：type 非空是 mapping 并集的收录依据
                String type = override.getType();
                if (type == null || type.isBlank()) {
                    throw new ServiceException(400, "新增字段 " + override.getField()
                            + " 必须携带类型（keyword/text/number/date）");
                }
                if (!TYPES.contains(type)) {
                    throw new ServiceException(400, "新增字段 " + override.getField() + " 类型非法: " + type);
                }
                if (++addedCount > properties.getField().getTenantMaxAdded()) {
                    throw new ServiceException(400, "单源租户新增字段数不能超过 "
                            + properties.getField().getTenantMaxAdded());
                }
            }
            if (override.getWeight() != null && override.getWeight() <= 0) {
                throw new ServiceException(400, "字段 " + override.getField() + " 权重须大于 0");
            }
            cleaned.put(override.getField(), override);
        }
        return SearchJson.toJson(new ArrayList<>(cleaned.values()));
    }

    /**
     * 平台配置 + 租户覆盖/新增 → 生效配置：按 field 匹配，覆盖项非 null 的
     * label/searchable/weight/highlightable/returned 生效（type 恒取平台值）；
     * 平台未声明且携带类型的条目为租户新增字段，追加到生效清单尾部。
     */
    public List<SearchFieldDescriptor> mergeTenant(List<SearchFieldDescriptor> platform,
                                                   List<SearchFieldDescriptor> override) {
        if (override == null || override.isEmpty()) {
            return platform;
        }
        Map<String, SearchFieldDescriptor> overridesByField = new LinkedHashMap<>();
        override.forEach(item -> overridesByField.put(item.getField(), item));
        List<SearchFieldDescriptor> merged = new ArrayList<>(platform.size() + override.size());
        for (SearchFieldDescriptor base : platform) {
            SearchFieldDescriptor over = overridesByField.get(base.getField());
            if (over == null) {
                merged.add(base);
                continue;
            }
            SearchFieldDescriptor item = new SearchFieldDescriptor(base.getField(),
                    over.getLabel() != null ? over.getLabel() : base.getLabel(),
                    base.getType(),
                    over.getSearchable() != null ? over.getSearchable() : base.getSearchable(),
                    over.getWeight() != null ? over.getWeight() : base.getWeight(),
                    over.getHighlightable() != null ? over.getHighlightable() : base.getHighlightable(),
                    over.getReturned() != null ? over.getReturned() : base.getReturned());
            merged.add(item);
        }
        Set<String> platformFields = platform.stream().map(SearchFieldDescriptor::getField)
                .collect(Collectors.toSet());
        for (SearchFieldDescriptor item : override) {
            if (item.getField() != null && !platformFields.contains(item.getField()) && item.getType() != null) {
                merged.add(item);
            }
        }
        return merged;
    }

    // ==================== 生效配置的消费视图 ====================

    /** 可检索字段及权重（text 全文 / keyword 全值精确；同名跨源由调用方按最大权重合并） */
    public Map<String, Double> searchableWeights(List<SearchFieldDescriptor> effective) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (SearchFieldDescriptor field : effective) {
            boolean searchable = field.getSearchable() != null && field.getSearchable();
            if (!searchable || field.getType() == null) {
                continue;
            }
            boolean searchableType = field.getType().equals(SearchFieldDescriptor.TYPE_TEXT)
                    || field.getType().equals(SearchFieldDescriptor.TYPE_KEYWORD);
            if (searchableType) {
                weights.merge(field.getField(), field.getWeight() == null ? 1.0 : field.getWeight(), Math::max);
            }
        }
        return weights;
    }

    /** 可高亮字段（仅 text；keyword/number/date 高亮无意义） */
    public List<String> highlightFields(List<SearchFieldDescriptor> effective) {
        return effective.stream()
                .filter(f -> f.getHighlightable() != null && f.getHighlightable())
                .filter(f -> SearchFieldDescriptor.TYPE_TEXT.equals(f.getType()))
                .map(SearchFieldDescriptor::getField)
                .toList();
    }

    /** 展示字段（returned；供搜索端按 label 渲染 extra 值） */
    public List<SearchFieldDescriptor> displayFields(List<SearchFieldDescriptor> effective) {
        return effective.stream()
                .filter(f -> f.getReturned() != null && f.getReturned())
                .toList();
    }

    /** 索引链路的平台字段配置（字段配置保存/比对等平台资产口径；无租户上下文） */
    public List<SearchFieldDescriptor> platformFields(SearchSource source) {
        return parsePlatform(source.getFieldsJson());
    }

    // ==================== mapping 并集（本租户配置 ∪ 本租户新增） ====================

    /**
     * 索引链路（mapping 生成 + 写入时 extra 键提升）的字段清单：本租户字段配置 ∪ 本租户新增字段。
     * 调用方处于归属租户上下文（扫描/索引写入均以 runAs 进入），search_tenant_field 行级隔离
     * 自动过滤——每租户 × 每源一个索引，mapping 完全租户自治，无跨租户并集。并集带短 TTL 缓存
     * （按租户 × 源隔离），租户保存/恢复时主动失效。
     */
    public MappingFields mappingFields(SearchSource source) {
        String sourceCode = source.getSourceCode();
        String cacheKey = currentTenantId() + ":" + sourceCode;
        CachedUnion cached = mappingUnionCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt() < UNION_TTL_MILLIS) {
            return new MappingFields(cached.fields(), cached.fingerprint());
        }
        List<SearchFieldDescriptor> platform = parsePlatform(source.getFieldsJson());
        Set<String> platformFields = platform.stream().map(SearchFieldDescriptor::getField)
                .collect(Collectors.toSet());
        Map<String, SearchFieldDescriptor> added = new LinkedHashMap<>();
        List<SearchTenantField> rows = tenantFieldMapper.selectList(
                new LambdaQueryWrapper<SearchTenantField>().eq(SearchTenantField::getSourceCode, sourceCode));
        for (SearchTenantField row : rows) {
            if (row.getFieldsJson() == null || row.getFieldsJson().isBlank()) {
                continue;
            }
            for (SearchFieldDescriptor item : SearchJson.parseList(row.getFieldsJson(), SearchFieldDescriptor.class)) {
                if (item.getField() == null || platformFields.contains(item.getField())
                        || item.getType() == null || added.containsKey(item.getField())) {
                    continue;
                }
                added.put(item.getField(), item);
            }
        }
        List<SearchFieldDescriptor> addedList = new ArrayList<>(added.values());
        int cap = maxPerSource();
        if (platform.size() + addedList.size() > cap) {
            addedList = addedList.subList(0, Math.max(0, cap - platform.size()));
            log.warn("[mse-search] 源 {} 租户 {} 字段并集超过上限 {}，新增字段被截断（请清理或调大 mse.search.field.max-per-source）",
                    sourceCode, currentTenantId(), cap);
        }
        List<SearchFieldDescriptor> fields = new ArrayList<>(platform.size() + addedList.size());
        fields.addAll(platform);
        fields.addAll(addedList);
        String fingerprint = (source.getFieldsJson() == null ? "" : source.getFieldsJson())
                + "|" + SearchJson.toJson(addedList);
        mappingUnionCache.put(cacheKey, new CachedUnion(System.currentTimeMillis(), fingerprint, fields));
        return new MappingFields(fields, fingerprint);
    }

    /** 租户字段保存/恢复后失效并集缓存（当前租户 × 源；配合 TTL 保证多实例最终一致） */
    public void evictMappingCache(String sourceCode) {
        mappingUnionCache.remove(currentTenantId() + ":" + sourceCode);
    }

    /** 当前租户（缺失视同平台 0，与插入填充口径一致） */
    private static long currentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        return tenantId == null ? 0L : tenantId;
    }

    private int maxPerSource() {
        return Math.max(1, properties.getField().getMaxPerSource());
    }

    /** mapping 并集：字段清单 + 指纹（fieldsJson 原文 + 租户新增序列化，供增量 putMapping 去重比对） */
    public record MappingFields(List<SearchFieldDescriptor> fields, String fingerprint) {
    }

    private record CachedUnion(long loadedAt, String fingerprint, List<SearchFieldDescriptor> fields) {
    }
}
