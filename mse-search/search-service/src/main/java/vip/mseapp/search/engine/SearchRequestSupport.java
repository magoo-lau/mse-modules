package vip.mseapp.search.engine;

import co.elastic.clients.elasticsearch.core.SearchRequest;

import java.io.StringReader;
import java.util.List;

/**
 * ES 检索请求构造（仅供 {@link EsSearchEngineClient} 触达的静态辅助，独立成类以便 mock/复用）。
 */
final class SearchRequestSupport {

    private SearchRequestSupport() {
    }

    static SearchRequest of(List<String> indexNames, String bodyJson) {
        return SearchRequest.of(r -> {
            r.index(indexNames);
            // 跨源检索允许部分索引缺席（新源尚未首扫建索引/被清空）：缺者按空集参与，不拖垮整次查询
            r.ignoreUnavailable(Boolean.TRUE);
            r.withJson(new StringReader(bodyJson));
            return r;
        });
    }
}
