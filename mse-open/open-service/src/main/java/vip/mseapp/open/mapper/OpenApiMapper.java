package vip.mseapp.open.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.mseapp.open.entity.OpenApiDef;

@Mapper
public interface OpenApiMapper extends BaseMapper<OpenApiDef> {

    /**
     * 物理删除（绕过 {@code @TableLogic} 软删）：目录退役清理专用——uk_tenant_code 含
     * deleted 列，软删后同 code 二次退役/再注册会撞唯一键。
     */
    @Delete("DELETE FROM open_api WHERE id = #{id}")
    int deletePhysically(@Param("id") Long id);
}
