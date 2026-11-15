package vip.mseapp.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.search.entity.SearchTask;

/** 任务 Mapper（平台级全局表，自动进租户白名单）。 */
@Mapper
public interface SearchTaskMapper extends BaseMapper<SearchTask> {
}
