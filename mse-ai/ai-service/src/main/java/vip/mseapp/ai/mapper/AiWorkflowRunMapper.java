package vip.mseapp.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.ai.entity.AiWorkflowRun;

/** 工作流运行历史 Mapper（tenant_id 由实体显式携带，租户插件自动隔离查询） */
@Mapper
public interface AiWorkflowRunMapper extends BaseMapper<AiWorkflowRun> {
}
