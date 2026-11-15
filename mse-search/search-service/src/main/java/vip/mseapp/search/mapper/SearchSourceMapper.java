package vip.mseapp.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.search.entity.SearchSource;

/** 可搜索源 Mapper（平台级全局表，自动进租户白名单）。 */
@Mapper
public interface SearchSourceMapper extends BaseMapper<SearchSource> {
}
