package vip.mseapp.search.engine;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.cat.indices.IndicesRecord;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.search.support.SearchJson;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 端口默认实现（elasticsearch-java 客户端，Boot BOM 仲裁版本）。
 *
 * <p>客户端由 Boot 自动装配构建（spring.elasticsearch.* 配置），本类只做 Map/JSON DSL 与
 * 类型化 API 的转换；引擎更换经 SearchEngineClient 端口整体替换，业务代码零改动。
 */
public class EsSearchEngineClient implements SearchEngineClient {

    private static final Logger log = LoggerFactory.getLogger(EsSearchEngineClient.class);

    private final ElasticsearchClient client;

    public EsSearchEngineClient(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public void ensureIndex(String indexName, Map<String, Object> settings, Map<String, Object> mappings) {
        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("settings", settings);
            body.put("mappings", mappings);
            String json = SearchJson.toJson(body);
            client.indices().create(c -> c.index(indexName).withJson(new StringReader(json)));
            log.info("[mse-search] 已创建索引 {}", indexName);
        } catch (Exception e) {
            throw new ServiceException(500, "创建索引失败 " + indexName + ": " + e.getMessage());
        }
    }

    @Override
    public void putMapping(String indexName, Map<String, Object> mappings) {
        try {
            String json = SearchJson.toJson(mappings);
            client.indices().putMapping(p -> p.index(indexName).withJson(new StringReader(json)));
        } catch (Exception e) {
            throw new ServiceException(500, "更新索引 mapping 失败 " + indexName + ": " + e.getMessage());
        }
    }

    @Override
    public void bulkIndex(String indexName, List<EngineDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        BulkRequest.Builder builder = new BulkRequest.Builder();
        for (EngineDoc doc : docs) {
            builder.operations(op -> op.index(idx -> idx.index(indexName).id(doc.id()).document(JsonData.of(doc.source()))));
        }
        try {
            BulkResponse response = client.bulk(builder.build());
            if (response.errors()) {
                List<String> failures = new ArrayList<>();
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        failures.add(item.id() + ": " + item.error().reason());
                    }
                }
                throw new ServiceException(500, "批量写入存在失败项: " + String.join("; ", failures));
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(500, "批量写入失败: " + e.getMessage());
        }
    }

    @Override
    public void bulkDelete(String indexName, List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        BulkRequest.Builder builder = new BulkRequest.Builder();
        for (String id : docIds) {
            builder.operations(op -> op.delete(d -> d.index(indexName).id(id)));
        }
        try {
            client.bulk(builder.build());
        } catch (Exception e) {
            throw new ServiceException(500, "批量删除失败: " + e.getMessage());
        }
    }

    @Override
    public long deleteByQuery(String indexName, Map<String, Object> query) {
        try {
            // query 本身就是裸查询 DSL，.query() 槽位会再包一层 "query"，此处不能重复包装（否则 ES 报 unknown query [query]）
            String json = SearchJson.toJson(query);
            var response = client.deleteByQuery(d -> d.index(indexName)
                    .query(q -> q.withJson(new StringReader(json)))
                    .refresh(true));
            return response.deleted();
        } catch (Exception e) {
            throw new ServiceException(500, "条件删除失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, Object> search(List<String> indexNames, Map<String, Object> requestBody) {
        try {
            String json = SearchJson.toJson(requestBody);
            var response = client.search(SearchRequestSupport.of(indexNames, json), Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            var hits = response.hits();
            result.put("total", hits.total() == null ? 0L : hits.total().value());
            List<Map<String, Object>> items = new ArrayList<>();
            for (var hit : hits.hits()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("_id", hit.id());
                item.put("_index", hit.index());
                item.put("_score", hit.score());
                item.put("_source", hit.source());
                if (hit.highlight() != null && !hit.highlight().isEmpty()) {
                    item.put("highlight", hit.highlight());
                }
                items.add(item);
            }
            result.put("hits", items);
            Map<String, Object> aggregations = new LinkedHashMap<>();
            if (response.aggregations() != null) {
                response.aggregations().forEach((name, agg) -> {
                    Map<String, Object> aggMap = new LinkedHashMap<>();
                    if (agg.isSterms()) {
                        List<Map<String, Object>> buckets = new ArrayList<>();
                        agg.sterms().buckets().array().forEach(bucket -> {
                            Map<String, Object> b = new LinkedHashMap<>();
                            b.put("key", plainScalar(bucket.key()));
                            b.put("count", bucket.docCount());
                            buckets.add(b);
                        });
                        aggMap.put("buckets", buckets);
                        aggregations.put(name, aggMap);
                    }
                });
            }
            result.put("aggregations", aggregations);
            return result;
        } catch (Exception e) {
            throw new ServiceException(500, "检索失败: " + e.getMessage());
        }
    }

    /** elasticsearch-java 9.x 起聚合桶 key 由 String 改为 FieldValue 联合类型，直接 put 会以对象 toString 泄漏到下游 JSON，按 kind 还原为普通标量 */
    private static Object plainScalar(FieldValue key) {
        return switch (key._kind()) {
            case String -> key.stringValue();
            case Long -> key.longValue();
            case Double -> key.doubleValue();
            case Boolean -> key.booleanValue();
            case Null -> null;
            case Any -> key.anyValue().toJson().toString();
        };
    }

    @Override
    public void forceMerge(String indexName) {
        try {
            client.indices().forcemerge(f -> f.index(indexName).maxNumSegments(1L));
        } catch (Exception e) {
            throw new ServiceException(500, "索引优化失败 " + indexName + ": " + e.getMessage());
        }
    }

    @Override
    public long docCount(String indexName) {
        try {
            return client.count(c -> c.index(indexName)).count();
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public boolean deleteIndex(String indexName) {
        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (!exists) {
                return false;
            }
            client.indices().delete(d -> d.index(indexName));
            log.info("[mse-search] 已删除索引 {}", indexName);
            return true;
        } catch (Exception e) {
            throw new ServiceException(500, "删除索引失败 " + indexName + ": " + e.getMessage());
        }
    }

    @Override
    public List<IndexInfo> listIndices(String pattern) {
        try {
            List<IndexInfo> result = new ArrayList<>();
            for (IndicesRecord record : client.cat().indices(c -> c.index(pattern)).indices()) {
                long docsCount = 0L;
                if (record.docsCount() != null) {
                    try {
                        docsCount = Long.parseLong(record.docsCount());
                    } catch (NumberFormatException ignored) {
                        // 关闭索引等场景 docsCount 不可用，按 0 展示
                    }
                }
                result.add(new IndexInfo(record.index(), record.health(),
                        record.status() == null ? "open" : record.status(), docsCount, record.storeSize()));
            }
            return result;
        } catch (Exception e) {
            throw new ServiceException(500, "查询索引清单失败: " + e.getMessage());
        }
    }

    @Override
    public boolean ping() {
        try {
            client.info();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
