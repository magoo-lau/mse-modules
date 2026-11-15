package vip.mseapp.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.mseapp.search.entity.SearchScanCursor;

/** 扫描游标 Mapper（租户隔离表：租户 × 源 × 分片粒度增量水位）。 */
@Mapper
public interface SearchScanCursorMapper extends BaseMapper<SearchScanCursor> {

    /** 物理删除（全量重建清游标专用）：uk_search_cursor_tenant 唯一键含逻辑删除墓碑，反复重建会累积撞键 */
    @Delete("DELETE FROM search_scan_cursor WHERE id = #{id}")
    int deletePhysicallyById(@Param("id") Long id);
}
