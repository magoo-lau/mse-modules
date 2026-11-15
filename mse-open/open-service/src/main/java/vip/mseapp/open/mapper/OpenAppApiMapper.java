package vip.mseapp.open.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.mseapp.open.entity.OpenAppApi;

@Mapper
public interface OpenAppApiMapper extends BaseMapper<OpenAppApi> {

    /**
     * 物理删除指定 API 在本世界的授权行（绕过 {@code @TableLogic} 软删）：
     * 目录退役清理时同步移除引用（uk_app_api 含 deleted，软删同撞键）。
     */
    @Delete("DELETE FROM open_app_api WHERE api_id = #{apiId} AND tenant_id = #{tenantId}")
    int deletePhysicallyByApi(@Param("apiId") Long apiId, @Param("tenantId") Long tenantId);
}
