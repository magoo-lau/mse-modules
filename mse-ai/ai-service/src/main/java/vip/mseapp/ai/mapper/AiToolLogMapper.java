package vip.mseapp.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.ai.entity.AiToolLog;

/** 工具调用审计 Mapper。 */
@Mapper
public interface AiToolLogMapper extends BaseMapper<AiToolLog> {
}
