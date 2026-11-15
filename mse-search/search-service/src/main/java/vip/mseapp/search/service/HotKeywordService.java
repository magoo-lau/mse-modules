package vip.mseapp.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.search.entity.SearchHotKeyword;
import vip.mseapp.search.mapper.SearchHotKeywordMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索热词：查询链路异步累加（select-then-upsert，并发重复插入时退化为更新），
 * 供搜索框联想与管理端运营分析。
 */
@Service
public class HotKeywordService {

    private static final Logger log = LoggerFactory.getLogger(HotKeywordService.class);

    private static final int MAX_KEYWORD_LENGTH = 64;

    private final SearchHotKeywordMapper hotKeywordMapper;
    private final java.util.concurrent.ExecutorService searchExecutor;

    public HotKeywordService(SearchHotKeywordMapper hotKeywordMapper,
                             java.util.concurrent.ExecutorService searchExecutor) {
        this.hotKeywordMapper = hotKeywordMapper;
        this.searchExecutor = searchExecutor;
    }

    /** 异步记录一次搜索（租户取当前上下文） */
    public void record(String keyword) {
        String kw = normalize(keyword);
        Long tenantId = TenantContext.getTenantId();
        if (kw == null || tenantId == null) {
            return;
        }
        searchExecutor.submit(() -> {
            try {
                TenantContext.runAs(tenantId, () -> upsert(kw));
            } catch (Exception e) {
                log.debug("[mse-search] 热词记录失败: {}", e.getMessage());
            }
        });
    }

    private void upsert(String kw) {
        SearchHotKeyword existing = hotKeywordMapper.selectOne(new LambdaQueryWrapper<SearchHotKeyword>()
                .eq(SearchHotKeyword::getKeyword, kw));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            SearchHotKeyword record = new SearchHotKeyword();
            record.setKeyword(kw);
            record.setHitCount(1L);
            record.setLastSearchTime(now);
            try {
                hotKeywordMapper.insert(record);
            } catch (Exception duplicate) {
                // 并发首插撞唯一键：退化为计数累加
                SearchHotKeyword raced = hotKeywordMapper.selectOne(new LambdaQueryWrapper<SearchHotKeyword>()
                        .eq(SearchHotKeyword::getKeyword, kw));
                if (raced != null) {
                    raced.setHitCount(raced.getHitCount() == null ? 1 : raced.getHitCount() + 1);
                    raced.setLastSearchTime(now);
                    hotKeywordMapper.updateById(raced);
                }
            }
            return;
        }
        existing.setHitCount(existing.getHitCount() == null ? 1 : existing.getHitCount() + 1);
        existing.setLastSearchTime(now);
        hotKeywordMapper.updateById(existing);
    }

    /** 热词榜分页（按命中次数降序，可选关键词模糊过滤） */
    public Page<SearchHotKeyword> page(long pageNum, long pageSize, String keyword) {
        return hotKeywordMapper.selectPage(new Page<>(pageNum, pageSize), new LambdaQueryWrapper<SearchHotKeyword>()
                .like(keyword != null && !keyword.isBlank(), SearchHotKeyword::getKeyword, keyword)
                .orderByDesc(SearchHotKeyword::getHitCount));
    }

    /** 联想词：命中前缀的热词 Top N */
    public List<String> topKeywords(String prefix, int limit) {
        List<SearchHotKeyword> hot = hotKeywordMapper.selectList(new LambdaQueryWrapper<SearchHotKeyword>()
                .orderByDesc(SearchHotKeyword::getHitCount)
                .last("LIMIT " + Math.min(Math.max(limit, 1) * 4, 100)));
        String p = normalize(prefix);
        return hot.stream()
                .map(SearchHotKeyword::getKeyword)
                .filter(kw -> p == null || p.isEmpty() || kw.startsWith(p))
                .limit(Math.min(Math.max(limit, 1), 20))
                .toList();
    }

    private static String normalize(String keyword) {
        if (keyword == null) {
            return null;
        }
        String kw = keyword.trim();
        if (kw.isEmpty() || kw.length() > MAX_KEYWORD_LENGTH) {
            return null;
        }
        return kw;
    }
}
