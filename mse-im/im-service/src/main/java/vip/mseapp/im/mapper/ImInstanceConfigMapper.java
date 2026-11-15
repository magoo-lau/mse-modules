package vip.mseapp.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.mseapp.im.entity.ImInstanceConfig;

/**
 * IM 实例台账 Mapper（每租户一行；租户行级隔离随插件自动生效）。
 */
@Mapper
public interface ImInstanceConfigMapper extends BaseMapper<ImInstanceConfig> {

    /** 物理删除（销毁实例专用）：@TableLogic 下常规 deleteById 是逻辑删除，
     *  唯一键 (tenant_id, deleted) 会随销毁次数累积，物理删除保证销毁后可重新开通 */
    @Delete("DELETE FROM im_instance_config WHERE id = #{id}")
    int deletePhysicallyById(@Param("id") Long id);
}
