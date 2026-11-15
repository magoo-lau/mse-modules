package vip.mseapp.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.mseapp.search.entity.SearchTenantField;

/** 租户字段覆盖 Mapper（租户隔离表，TenantLine 按上下文自动过滤）。 */
@Mapper
public interface SearchTenantFieldMapper extends BaseMapper<SearchTenantField> {

    /** 物理删除（恢复平台默认专用）：uk_search_tenant_field 唯一键含逻辑删除墓碑，覆盖/恢复反复切换会累积撞键 */
    @Delete("DELETE FROM search_tenant_field WHERE id = #{id}")
    int deletePhysicallyById(@Param("id") Long id);
}
