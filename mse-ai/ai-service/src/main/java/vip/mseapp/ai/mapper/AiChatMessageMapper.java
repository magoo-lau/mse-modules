package vip.mseapp.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.ai.entity.AiChatMessage;

/** 对话消息 Mapper。 */
@Mapper
public interface AiChatMessageMapper extends BaseMapper<AiChatMessage> {
}
