package vip.mseapp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import vip.mseapp.ai.entity.AiBizModule;
import vip.mseapp.ai.mapper.AiBizModuleMapper;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.List;

/**
 * 业务模块注册中心（平台全局目录）：biz_query 路由门面的模块目录数据源。
 *
 * <p>模块目录=跨租户全局视图（runIgnore 合法场景：平台登记源/全局目录）——仅平台行
 * （tenant_id=0），读取走 runIgnore 落 primary（平台库），无租户副本；声明来源
 * local（单体启动扫描）/ rpc（微服务推送），登记治理见 LocalBizModuleAutoRegistrar。
 */
@Service
public class AiBizModuleService {

    public static final String SOURCE_LOCAL = "local";

    public static final String SOURCE_RPC = "rpc";

    private final AiBizModuleMapper moduleMapper;

    public AiBizModuleService(AiBizModuleMapper moduleMapper) {
        this.moduleMapper = moduleMapper;
    }

    /** 全部模块（管理端列表，含停用） */
    public List<AiBizModule> listAll() {
        return TenantContext.runIgnore(() -> moduleMapper.selectList(new LambdaQueryWrapper<AiBizModule>()
                .eq(AiBizModule::getTenantId, MseConstants.PLATFORM_TENANT_ID)
                .orderByAsc(AiBizModule::getModuleCode)));
    }

    /** 启用模块目录（biz_query list_modules 数据源，按编码排序） */
    public List<AiBizModule> listEnabled() {
        return TenantContext.runIgnore(() -> moduleMapper.selectList(new LambdaQueryWrapper<AiBizModule>()
                .eq(AiBizModule::getTenantId, MseConstants.PLATFORM_TENANT_ID)
                .eq(AiBizModule::getStatus, 0)
                .orderByAsc(AiBizModule::getModuleCode)));
    }

    /** 按编码取启用模块（biz_query 转发寻址）；不存在或已停用抛业务错误并列出可用模块 */
    public AiBizModule getEnabled(String moduleCode) {
        AiBizModule module = TenantContext.runIgnore(() -> moduleMapper.selectOne(
                new LambdaQueryWrapper<AiBizModule>()
                        .eq(AiBizModule::getModuleCode, moduleCode)
                        .eq(AiBizModule::getTenantId, MseConstants.PLATFORM_TENANT_ID)
                        .last("LIMIT 1")));
        if (module == null || module.getStatus() == null || module.getStatus() != 0) {
            List<String> available = listEnabled().stream().map(AiBizModule::getModuleCode).toList();
            throw new ServiceException(400, "未知的业务模块：" + moduleCode
                    + "。可用模块：" + (available.isEmpty() ? "（暂无，确认业务积木已部署/聚合）" : String.join("、", available))
                    + "。可先调用 action=list_modules 查看模块目录");
        }
        return module;
    }
}
