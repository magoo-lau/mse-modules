package vip.mseapp.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.data.entity.DatSyncJob;

@Mapper
public interface DatSyncJobMapper extends BaseMapper<DatSyncJob> {
}
