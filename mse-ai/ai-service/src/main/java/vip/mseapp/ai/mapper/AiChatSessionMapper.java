package vip.mseapp.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.ai.entity.AiChatSession;

/** 对话会话 Mapper。 */
@Mapper
public interface AiChatSessionMapper extends BaseMapper<AiChatSession> {
}
