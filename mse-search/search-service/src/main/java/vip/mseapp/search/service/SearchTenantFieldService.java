package vip.mseapp.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.search.api.SearchFieldDescriptor;
import vip.mseapp.search.api.dto.SearchFieldCatalogEntry;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.entity.SearchTenantField;
import vip.mseapp.search.mapper.SearchTenantFieldMapper;
import vip.mseapp.search.support.SearchJson;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 租户字段覆盖/新增：租户对单个源的查询层微调（可搜/权重/高亮/展示/显示名）与
 * 从业务字段目录新增检索字段（携带目录类型进入 mapping 并集，仅本租户检索生效）。
 *
 * <p>tenantId 由 BaseEntity 携带（插件按上下文自动隔离与填充），行定位即「当前租户 × sourceCode」；
 * 覆盖项只能引用平台已声明字段（类型属平台 mapping 资产不可改），新增项必须存在于业务字段目录
 * （保存时实时校验）且不得触碰系统保留字段——mapping 与索引数据是平台全局资产。
 */
@Service
public class SearchTenantFieldService {

    private final SearchTenantFieldMapper tenantFieldMapper;
    private final SearchSourceService sourceService;
    private final SearchFieldConfigService fieldConfigService;
    private final SearchFieldCatalogService fieldCatalogService;

    public SearchTenantFieldService(SearchTenantFieldMapper tenantFieldMapper,
                                    SearchSourceService sourceService,
                                    SearchFieldConfigService fieldConfigService,
                                    SearchFieldCatalogService fieldCatalogService) {
        this.tenantFieldMapper = tenantFieldMapper;
        this.sourceService = sourceService;
        this.fieldConfigService = fieldConfigService;
        this.fieldCatalogService = fieldCatalogService;
    }

    /** 当前租户的全部覆盖行（前端列表） */
    public List<SearchTenantField> list() {
        return tenantFieldMapper.selectList(new LambdaQueryWrapper<SearchTenantField>()
                .orderByAsc(SearchTenantField::getSourceCode));
    }

    /** 启用源 ×（平台字段 + 当前租户覆盖/新增）视图（租户配置页主数据） */
    public List<SourceFieldsView> sourcesWithFields() {
        Map<String, List<SearchFieldDescriptor>> overrides = new LinkedHashMap<>();
        list().forEach(row -> {
            if (row.getFieldsJson() != null && !row.getFieldsJson().isBlank()) {
                // 原始解析（不走 normalize）：覆盖项未设置的属性须保持 null 才能在合并时沿用平台值
                overrides.put(row.getSourceCode(),
                        SearchJson.parseList(row.getFieldsJson(), SearchFieldDescriptor.class));
            }
        });
        return sourceService.listEnabled().stream().map(source ->
                new SourceFieldsView(source.getSourceCode(), source.getSourceName(), source.getDomain(),
                        fieldConfigService.parsePlatform(source.getFieldsJson()),
                        overrides.get(source.getSourceCode()))).toList();
    }

    /**
     * 保存当前租户对指定源的覆盖/新增（空清单 = 等价恢复默认，删除覆盖行）。
     * 新增字段（平台未声明）实时对照业务字段目录校验：目录不可用或字段不在目录中则拒绝——
     * 新增字段的类型来自目录，且 mapping 并集只应收录业务侧真实暴露的字段。
     */
    @Transactional(rollbackFor = Exception.class)
    public void save(String sourceCode, List<SearchFieldDescriptor> overrides) {
        SearchSource source = sourceService.selectByCode(sourceCode);
        if (source == null) {
            throw new ServiceException(404, "可搜索源不存在: " + sourceCode);
        }
        if (overrides == null || overrides.isEmpty()) {
            reset(sourceCode);
            return;
        }
        List<SearchFieldDescriptor> platform = fieldConfigService.parsePlatform(source.getFieldsJson());
        String fieldsJson = fieldConfigService.serializeTenantOverride(platform, overrides);
        checkAddedAgainstCatalog(source, platform, overrides);
        SearchTenantField existing = selectBySource(sourceCode);
        if (existing == null) {
            SearchTenantField row = new SearchTenantField();
            row.setSourceCode(sourceCode);
            row.setFieldsJson(fieldsJson);
            tenantFieldMapper.insert(row);
        } else {
            existing.setFieldsJson(fieldsJson);
            tenantFieldMapper.updateById(existing);
        }
        fieldConfigService.evictMappingCache(sourceCode);
    }

    /** 恢复平台默认（删除覆盖行；物理删除防 uk_search_tenant_field 墓碑累积撞键） */
    @Transactional(rollbackFor = Exception.class)
    public void reset(String sourceCode) {
        SearchTenantField existing = selectBySource(sourceCode);
        if (existing != null) {
            tenantFieldMapper.deletePhysicallyById(existing.getId());
        }
        fieldConfigService.evictMappingCache(sourceCode);
    }

    /** 新增字段（平台未声明）须在业务字段目录中存在；目录不可用时拒绝保存（fail fast，防误收录） */
    private void checkAddedAgainstCatalog(SearchSource source, List<SearchFieldDescriptor> platform,
                                          List<SearchFieldDescriptor> overrides) {
        Set<String> platformFields = platform.stream().map(SearchFieldDescriptor::getField)
                .collect(Collectors.toSet());
        Set<String> addedFields = overrides.stream()
                .filter(o -> o.getField() != null && !o.getField().isBlank() && !platformFields.contains(o.getField()))
                .map(SearchFieldDescriptor::getField)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (addedFields.isEmpty()) {
            return;
        }
        SearchFieldCatalogService.CatalogFetch fetch = fieldCatalogService.fetch(source, SearchFieldCatalogService.requestWorldId());
        if (!fetch.available()) {
            throw new ServiceException(400, "业务字段目录暂不可用，无法校验新增字段：" + fetch.message());
        }
        Set<String> catalogFields = fetch.entries().stream()
                .map(SearchFieldCatalogEntry::getField)
                .collect(Collectors.toSet());
        for (String field : addedFields) {
            if (!catalogFields.contains(field)) {
                throw new ServiceException(400, "字段 " + field + " 不在业务字段目录中，租户不能新增");
            }
        }
    }

    /** 租户字段目录视图：按源拉取业务目录并标注平台收录 / 本租户新增 / 可收录 */
    public SearchFieldCatalogService.CatalogView catalog(String sourceCode) {
        SearchSource source = sourceService.selectByCode(sourceCode);
        if (source == null) {
            throw new ServiceException(404, "可搜索源不存在: " + sourceCode);
        }
        List<SearchFieldDescriptor> platform = fieldConfigService.parsePlatform(source.getFieldsJson());
        Set<String> platformFields = platform.stream().map(SearchFieldDescriptor::getField)
                .collect(Collectors.toSet());
        Set<String> tenantAdded = new LinkedHashSet<>();
        SearchTenantField row = selectBySource(sourceCode);
        if (row != null && row.getFieldsJson() != null && !row.getFieldsJson().isBlank()) {
            SearchJson.parseList(row.getFieldsJson(), SearchFieldDescriptor.class).stream()
                    .filter(item -> item.getField() != null && item.getType() != null
                            && !platformFields.contains(item.getField()))
                    .map(SearchFieldDescriptor::getField)
                    .forEach(tenantAdded::add);
        }
        return fieldCatalogService.tenantView(source, platformFields, tenantAdded);
    }

    /** 当前行定位（tenant_id 由租户插件自动注入条件） */
    private SearchTenantField selectBySource(String sourceCode) {
        return tenantFieldMapper.selectOne(new LambdaQueryWrapper<SearchTenantField>()
                .eq(SearchTenantField::getSourceCode, sourceCode));
    }

    /** 租户配置页的源视图：平台字段（基线）+ 当前租户覆盖/新增（可空） */
    public record SourceFieldsView(String sourceCode, String sourceName, String domain,
                                   List<SearchFieldDescriptor> platformFields,
                                   List<SearchFieldDescriptor> overrideFields) {
    }
}
