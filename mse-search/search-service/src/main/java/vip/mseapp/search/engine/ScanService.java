package vip.mseapp.search.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.search.api.dto.FetchBatchRequest;
import vip.mseapp.search.api.dto.FetchBatchResult;
import vip.mseapp.search.config.SearchProperties;
import vip.mseapp.search.dispatch.SearchProviderDispatcher;
import vip.mseapp.search.entity.SearchScanCursor;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.mapper.SearchScanCursorMapper;
import vip.mseapp.search.mapper.SearchSourceMapper;
import vip.mseapp.search.service.SearchIndexService;
import vip.mseapp.search.support.SearchJson;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 扫描执行器：按「租户 × 源 × 分片」拉取增量文档批次并落索引，游标逐批持久化（崩溃后从水位续扫）。
 * 调用方（扫描调度器/任务执行）处于归属租户上下文——源行/游标经路由落归属库，拉取请求携带
 * 租户编号（供数端按其过滤数据）。
 *
 * <p>忙时限流（批间 sleep），闲时窗口内全速。异常计入游标 failCount/lastError 供管理端观测，
 * 不阻断其他分片。
 */
@Component
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final SearchSourceMapper sourceMapper;
    private final SearchScanCursorMapper cursorMapper;
    private final SearchProviderDispatcher dispatcher;
    private final SearchIndexService indexService;
    private final SearchProperties properties;
    private final IdleWindows idleWindows;

    public ScanService(SearchSourceMapper sourceMapper, SearchScanCursorMapper cursorMapper,
                       SearchProviderDispatcher dispatcher, SearchIndexService indexService,
                       SearchProperties properties) {
        this.sourceMapper = sourceMapper;
        this.cursorMapper = cursorMapper;
        this.dispatcher = dispatcher;
        this.indexService = indexService;
        this.properties = properties;
        this.idleWindows = new IdleWindows(properties.getIdleWindow());
    }

    /**
     * 扫描一个源（全部分片），分片失败不阻断其他分片（调度通道语义）。
     *
     * @param maxBatches       本轮最大批次数（防雪崩；&lt;=0 表示不限，全量重建用）
     * @param progressConsumer 进度回调（0-100，可空；全量重建任务回写进度）
     * @return 本轮处理文档数
     */
    public long scanSource(String sourceCode, int maxBatches, IntConsumer progressConsumer) {
        return scanSource(sourceCode, maxBatches, progressConsumer, null);
    }

    /**
     * 扫描一个源（全部分片），可选收集分片失败。
     *
     * @param failures         分片失败收集器（可空；任务通道传入以便感知失败置失败态，
     *                         调度通道传 null 维持「计入游标不阻断」语义）
     * @return 本轮处理文档数
     */
    public long scanSource(String sourceCode, int maxBatches, IntConsumer progressConsumer,
                           List<ShardFailure> failures) {
        SearchSource source = sourceMapper.selectOne(new LambdaQueryWrapper<SearchSource>()
                .eq(SearchSource::getSourceCode, sourceCode));
        if (source == null) {
            throw new ServiceException(404, "可搜索源不存在: " + sourceCode);
        }
        int shardCount = source.getShardCount() == null || source.getShardCount() < 1 ? 1 : source.getShardCount();
        long processed = 0;
        int shardsDone = 0;
        for (int shardIndex = 0; shardIndex < shardCount; shardIndex++) {
            try {
                processed += scanShard(source, shardIndex, shardCount, maxBatches);
            } catch (Exception e) {
                if (failures != null) {
                    failures.add(new ShardFailure(shardIndex,
                            e.getMessage() == null ? "未知异常" : e.getMessage()));
                }
                markCursorFailure(source.getSourceCode(), shardIndex, e.getMessage());
                log.warn("[mse-search] 源 {} 分片 {} 扫描失败: {}", sourceCode, shardIndex, e.getMessage());
            } finally {
                shardsDone++;
                if (progressConsumer != null) {
                    progressConsumer.accept(Math.min(99, shardsDone * 100 / shardCount));
                }
            }
        }
        if (progressConsumer != null) {
            progressConsumer.accept(100);
        }
        return processed;
    }

    /** 单分片扫描失败记录（任务通道回填失败原因用） */
    public record ShardFailure(int shardIndex, String error) {
    }

    private long scanShard(SearchSource source, int shardIndex, int shardCount, int maxBatches) throws Exception {
        SearchScanCursor cursor = loadOrCreateCursor(source.getSourceCode(), shardIndex);
        int batchSize = source.getBatchSize() == null ? properties.getScan().getDefaultBatchSize() : source.getBatchSize();
        long processed = 0;
        int batches = 0;
        while (maxBatches <= 0 || batches < maxBatches) {
            FetchBatchRequest request = new FetchBatchRequest();
            request.setSourceCode(source.getSourceCode());
            request.setTenantId(TenantContext.getTenantId());
            request.setShardIndex(shardIndex);
            request.setShardCount(shardCount);
            request.setWatermark(cursor.getWatermark() == null ? "0" : cursor.getWatermark());
            request.setBatchSize(batchSize);
            String responseJson = dispatcher.fetch(source.getProviderGroup(), SearchJson.toJson(request));
            FetchBatchResult result = SearchJson.parse(responseJson, FetchBatchResult.class);
            int docSize = result.getDocs() == null ? 0 : result.getDocs().size();
            if (docSize > 0) {
                indexService.indexDocs(source, TenantContext.getTenantId(), result.getDocs());
                processed += docSize;
            }
            batches++;
            if (result.getNextWatermark() != null) {
                cursor.setWatermark(result.getNextWatermark());
                cursorMapper.updateById(cursor);
            }
            if (!result.isHasMore()) {
                break;
            }
            sleepQuietly(batchInterval());
        }
        cursor.setLastScanTime(LocalDateTime.now());
        cursor.setFailCount(0);
        cursor.setLastError(null);
        cursorMapper.updateById(cursor);
        return processed;
    }

    /** 忙时限流、闲时全速 */
    private long batchInterval() {
        return idleWindows.contains(java.time.LocalTime.now()) ? 0 : properties.getScan().getBatchIntervalMs();
    }

    private SearchScanCursor loadOrCreateCursor(String sourceCode, int shardIndex) {
        SearchScanCursor cursor = cursorMapper.selectOne(new LambdaQueryWrapper<SearchScanCursor>()
                .eq(SearchScanCursor::getSourceCode, sourceCode)
                .eq(SearchScanCursor::getShardIndex, shardIndex));
        if (cursor == null) {
            cursor = new SearchScanCursor();
            cursor.setSourceCode(sourceCode);
            cursor.setShardIndex(shardIndex);
            cursor.setWatermark("0");
            cursor.setFailCount(0);
            cursorMapper.insert(cursor);
        }
        return cursor;
    }

    private void markCursorFailure(String sourceCode, int shardIndex, String error) {
        try {
            SearchScanCursor cursor = cursorMapper.selectOne(new LambdaQueryWrapper<SearchScanCursor>()
                    .eq(SearchScanCursor::getSourceCode, sourceCode)
                    .eq(SearchScanCursor::getShardIndex, shardIndex));
            if (cursor != null) {
                cursor.setFailCount(cursor.getFailCount() == null ? 1 : cursor.getFailCount() + 1);
                cursor.setLastError(error == null ? null : error.substring(0, Math.min(480, error.length())));
                cursorMapper.updateById(cursor);
            }
        } catch (Exception ignored) {
            // 失败标记本身失败不影响主流程
        }
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 全量重建：清空源游标（水位归零，扫描时重建） */
    public void resetCursors(String sourceCode) {
        List<SearchScanCursor> cursors = cursorMapper.selectList(new LambdaQueryWrapper<SearchScanCursor>()
                .eq(SearchScanCursor::getSourceCode, sourceCode));
        for (SearchScanCursor cursor : cursors) {
            // 物理删除：逻辑删除墓碑会在 uk_search_cursor_tenant(tenant_id, source_code, shard_index, deleted)
            // 上累积——第二次全量重建即报 Duplicate entry
            cursorMapper.deletePhysicallyById(cursor.getId());
        }
    }
}
