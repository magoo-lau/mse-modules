package vip.mseapp.search.scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.mseapp.api.system.RemoteDataScopeService;
import vip.mseapp.api.system.dto.DataScopeDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.UserContext;
import vip.mseapp.mybatis.datascope.DataScopeParam;

import java.util.HashSet;

/**
 * RPC 数据范围解析（微服务形态）：经 api-system 契约 {@link RemoteDataScopeService} 按
 * 当前登录用户编号解析（mse-system 实现，租户经 Dubbo attachment 隐式透传）。
 *
 * <p>降级语义（宁少不漏）：解析异常/契约缺失时按「仅本人」过滤并告警；无用户上下文
 * （系统内部调用）返回 null 不过滤，与底座 DataScopeResolverImpl 一致。
 */
public class RpcDataScopeResolver implements SearchDataScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(RpcDataScopeResolver.class);

    @RpcReference
    private RemoteDataScopeService remoteDataScopeService;

    @Override
    public DataScopeParam resolve() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return null;
        }
        DataScopeDTO dto;
        try {
            dto = remoteDataScopeService.resolve(userId);
        } catch (Throwable e) {
            log.warn("[mse-search] 数据范围解析失败，降级为仅本人过滤: {}", e.getMessage());
            return new DataScopeParam(DataScopeParam.Type.SELF, userId, null, null);
        }
        if (dto == null) {
            return null;
        }
        if (DataScopeDTO.TYPE_SELF.equals(dto.getType())) {
            return new DataScopeParam(DataScopeParam.Type.SELF, dto.getUserId(), dto.getDeptId(), null);
        }
        // CUSTOM：部门并集已含本部门及以下展开结果
        return new DataScopeParam(DataScopeParam.Type.CUSTOM, dto.getUserId(), dto.getDeptId(),
                dto.getDeptIds() == null ? new HashSet<>() : new HashSet<>(dto.getDeptIds()));
    }
}
