package vip.mseapp.search.service;

import org.springframework.stereotype.Service;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.search.api.SearchFieldDescriptor;
import vip.mseapp.search.api.SearchIndexPayload;
import vip.mseapp.search.api.dto.SearchDocDTO;
import vip.mseapp.search.engine.EsIndexManager;
import vip.mseapp.search.engine.SearchEngineClient;
import vip.mseapp.search.entity.SearchSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引写入服务：扫描通道（SearchDocDTO 批量）与 MQ 通道（SearchIndexPayload 单条）统一落索引。
 *
 * <p>文档 _id = {tenantId}:{sourceCode}:{bizId}（upsert 幂等）；索引按「租户 × 源」物理分桶
 * （每租户每源一个索引），文档 tenantId 字段保留为查询侧防御过滤。源关闭个人数据过滤时文档
 * visibleType 一律写 all，查询侧无需感知源差异。字段配置（源配置 ∪ 本租户新增，见
 * SearchFieldConfigService#mappingFields）声明的字段在写入时从 extra 提升为顶层映射字段
 * （可检索/高亮依赖顶层 text/keyword 映射，flattened extra 不支持加权）。
 */
@Service
public class SearchIndexService {

    /** 可见类型：公开文档（全员可见，不参与数据范围过滤） */
    public static final String VISIBLE_TYPE_ALL = "all";

    /** 可见类型：受数据范围过滤（默认） */
    public static final String VISIBLE_TYPE_NORMAL = "normal";

    private final EsIndexManager indexManager;
    private final SearchEngineClient engineClient;
    private final SearchSourceService sourceService;
    private final SearchFieldConfigService fieldConfigService;

    public SearchIndexService(EsIndexManager indexManager, SearchEngineClient engineClient,
                              SearchSourceService sourceService, SearchFieldConfigService fieldConfigService) {
        this.indexManager = indexManager;
        this.engineClient = engineClient;
        this.sourceService = sourceService;
        this.fieldConfigService = fieldConfigService;
    }

    /** 扫描通道：批量 upsert（deleted=true 的走删除）。调用方处于归属租户上下文（runAs） */
    public void indexDocs(SearchSource source, Long tenantId, List<SearchDocDTO> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        // 租户化扫描护栏：仅收录与扫描租户一致的文档——旧版供数器按全租户拉取的异租户文档在此丢弃
        List<SearchDocDTO> scoped = docs.stream()
                .filter(doc -> tenantId != null && tenantId.equals(doc.getTenantId()))
                .toList();
        if (scoped.isEmpty()) {
            return;
        }
        indexManager.ensureIndex(source, tenantId);
        // 字段提升与 mapping 同口径（源配置 ∪ 本租户新增）：声明字段从 extra 提升为顶层映射字段
        List<SearchFieldDescriptor> fields = fieldConfigService.mappingFields(source).fields();
        List<SearchEngineClient.EngineDoc> upserts = new ArrayList<>();
        List<String> deletes = new ArrayList<>();
        for (SearchDocDTO doc : scoped) {
            validateDoc(doc);
            if (doc.isDeleted()) {
                deletes.add(docId(doc.getTenantId(), source.getSourceCode(), doc.getBizId()));
            } else {
                upserts.add(new SearchEngineClient.EngineDoc(
                        docId(doc.getTenantId(), source.getSourceCode(), doc.getBizId()),
                        sourceMap(source, doc.getTenantId(), doc.getBizId(), doc.getTitle(), doc.getContent(),
                                doc.getExtra(), doc.getUpdateTime(), doc.getOwnerUserId(), doc.getDeptId(),
                                doc.getVisibleType(), doc.getVisibleUserIds(), fields)));
            }
        }
        String indexName = indexManager.indexName(source.getSourceCode(), tenantId);
        engineClient.bulkIndex(indexName, upserts);
        engineClient.bulkDelete(indexName, deletes);
    }

    /** MQ 通道：单条 upsert（源未登记抛错触发 MQ 重投等播种自愈；已停用告警后丢弃） */
    public void applyUpsert(Long tenantId, SearchIndexPayload payload) {
        SearchSource source = requireEnabledSource(payload.getSourceCode());
        if (source == null) {
            return;
        }
        indexManager.ensureIndex(source, tenantId);
        String indexName = indexManager.indexName(source.getSourceCode(), tenantId);
        engineClient.bulkIndex(indexName, List.of(new SearchEngineClient.EngineDoc(
                docId(tenantId, source.getSourceCode(), payload.getBizId()),
                sourceMap(source, tenantId, payload.getBizId(), payload.getTitle(), payload.getContent(),
                        payload.getExtra(), payload.getUpdateTime(), payload.getOwnerUserId(), payload.getDeptId(),
                        payload.getVisibleType(), payload.getVisibleUserIds(),
                        fieldConfigService.mappingFields(source).fields()))));
    }

    /** MQ 通道：单条删除（源未登记抛错触发 MQ 重投等播种自愈；已停用告警后丢弃） */
    public void applyDelete(Long tenantId, SearchIndexPayload payload) {
        SearchSource source = requireEnabledSource(payload.getSourceCode());
        if (source == null) {
            return;
        }
        engineClient.bulkDelete(indexManager.indexName(payload.getSourceCode(), tenantId),
                List.of(docId(tenantId, payload.getSourceCode(), payload.getBizId())));
    }

    /** 源必须已登记（缺失抛 404 → MQ 重投，等目录扇出播种后自愈） */
    private SearchSource requireSource(String sourceCode) {
        SearchSource source = sourceService.selectByCode(sourceCode);
        if (source == null) {
            throw new ServiceException(404, "可搜索源未登记: " + sourceCode);
        }
        return source;
    }

    /**
     * 已启用的源：存在但停用返回 null（调用方告警后丢弃事件——停用是租户的显式意志，
     * 重投永远不会成功）；未登记抛 404（播种完成后重投成功）。
     */
    private SearchSource requireEnabledSource(String sourceCode) {
        SearchSource source = requireSource(sourceCode);
        return source.getStatus() == SearchSource.STATUS_ENABLED ? source : null;
    }

    private void validateDoc(SearchDocDTO doc) {
        if (doc.getTenantId() == null) {
            throw new ServiceException(400, "文档缺少 tenantId（扫描链路必须逐条携带租户归属）");
        }
        if (doc.getBizId() == null || doc.getBizId().isBlank()) {
            throw new ServiceException(400, "文档缺少 bizId");
        }
    }

    private String docId(Long tenantId, String sourceCode, String bizId) {
        return tenantId + ":" + sourceCode + ":" + bizId;
    }

    private Map<String, Object> sourceMap(SearchSource source, Long tenantId, String bizId, String title,
                                          String content, Map<String, Object> extra, String updateTime,
                                          Long ownerUserId, Long deptId, String visibleType,
                                          List<Long> visibleUserIds, List<SearchFieldDescriptor> fields) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tenantId", String.valueOf(tenantId));
        map.put("sourceCode", source.getSourceCode());
        map.put("bizId", bizId);
        map.put("title", title);
        map.put("content", content);
        map.put("extra", extra);
        // 平台字段配置：声明字段从 extra 提升为顶层（mapping 已声明；值缺失则跳过，稀疏无害）
        for (SearchFieldDescriptor field : fields) {
            Object value = extra == null ? null : extra.get(field.getField());
            if (value != null) {
                map.put(field.getField(), value);
            }
        }
        map.put("permissionCode", source.getPermissionCode());
        map.put("ownerUserId", ownerUserId == null ? null : String.valueOf(ownerUserId));
        map.put("deptId", deptId == null ? null : String.valueOf(deptId));
        // 源关闭个人数据过滤 → 文档一律公开；否则缺省 normal 参与范围过滤
        boolean dataScopeEnabled = source.getDataScopeEnabled() == null || source.getDataScopeEnabled() == 1;
        if (!dataScopeEnabled) {
            map.put("visibleType", VISIBLE_TYPE_ALL);
        } else {
            map.put("visibleType", visibleType == null || visibleType.isBlank() ? VISIBLE_TYPE_NORMAL : visibleType);
        }
        map.put("visibleUserIds", visibleUserIds == null ? null
                : visibleUserIds.stream().map(String::valueOf).toList());
        map.put("updateTime", updateTime);
        return map;
    }
}
