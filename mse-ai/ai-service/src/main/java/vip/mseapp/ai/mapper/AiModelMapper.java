package vip.mseapp.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.ai.entity.AiModel;

/** 模型配置 Mapper（租户域表，各会话世界自有行，见 AiModelService）。 */
@Mapper
public interface AiModelMapper extends BaseMapper<AiModel> {
}
