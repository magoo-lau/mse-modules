package vip.mseapp.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.search.entity.SearchHotKeyword;

/** 搜索热词 Mapper（租户隔离表）。 */
@Mapper
public interface SearchHotKeywordMapper extends BaseMapper<SearchHotKeyword> {
}
