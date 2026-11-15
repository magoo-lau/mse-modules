package vip.mseapp.data.api.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 数据 API 调用结果。
 */
public class DataApiInvokeResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 列名（有序） */
    private List<String> columns;

    /** 数据行 */
    private List<Map<String, Object>> rows;

    /** 是否还有更多（分页场景） */
    private boolean hasMore;

    /** 耗时毫秒 */
    private Long costMs;

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    public Long getCostMs() {
        return costMs;
    }

    public void setCostMs(Long costMs) {
        this.costMs = costMs;
    }
}
