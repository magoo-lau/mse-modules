package vip.mseapp.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.mseapp.im.entity.ImWorkbenchItem;

/**
 * IM 聊天客户端工作台入口配置 Mapper。
 */
@Mapper
public interface ImWorkbenchItemMapper extends BaseMapper<ImWorkbenchItem> {

    /** 物理删除（覆盖式保存专用）：逻辑删除墓碑会在 (tenant_id, path, deleted) 唯一键上累积撞键 */
    @Delete("DELETE FROM im_workbench_item WHERE id = #{id}")
    int deletePhysicallyById(@Param("id") Long id);
}
