package vip.mseapp.search.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 增量拉取结果（业务 Provider → mse-search）。
 *
 * <p>hasMore=true 时 nextWatermark 必填（游标推进锚点）；分页拉取直至 hasMore=false。
 */
public class FetchBatchResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<SearchDocDTO> docs;

    /** 推进后的水位（通常取本批最大增量列值） */
    private String nextWatermark;

    /** 是否还有更多数据 */
    private boolean hasMore;

    public List<SearchDocDTO> getDocs() {
        return docs;
    }

    public void setDocs(List<SearchDocDTO> docs) {
        this.docs = docs;
    }

    public String getNextWatermark() {
        return nextWatermark;
    }

    public void setNextWatermark(String nextWatermark) {
        this.nextWatermark = nextWatermark;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }
}
