package vip.mseapp.search.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 业务字段目录结果（RemoteSearchDataProvider#fieldCatalog 的响应结构）。
 *
 * <p>entries 为可收录字段清单（同名字段同类型已合并）；conflicts 为类型冲突被排除的字段。
 * 目录为业务侧实时生成，mse-search 按需拉取、不落库——平台/租户勾选后才进入字段配置。
 */
public class SearchFieldCatalogResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目录条目超限被截断时置 true（业务侧保护） */
    private boolean truncated;

    private List<SearchFieldCatalogEntry> entries;

    private List<SearchFieldCatalogConflict> conflicts;

    public SearchFieldCatalogResult() {
    }

    public SearchFieldCatalogResult(boolean truncated, List<SearchFieldCatalogEntry> entries,
                                    List<SearchFieldCatalogConflict> conflicts) {
        this.truncated = truncated;
        this.entries = entries;
        this.conflicts = conflicts;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public List<SearchFieldCatalogEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<SearchFieldCatalogEntry> entries) {
        this.entries = entries;
    }

    public List<SearchFieldCatalogConflict> getConflicts() {
        return conflicts;
    }

    public void setConflicts(List<SearchFieldCatalogConflict> conflicts) {
        this.conflicts = conflicts;
    }
}
