package vip.mseapp.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.ai.entity.AiAgent;

/** 智能体 Mapper（租户域表，读写走会话世界边界，见 AiAgentService）。 */
@Mapper
public interface AiAgentMapper extends BaseMapper<AiAgent> {
}
