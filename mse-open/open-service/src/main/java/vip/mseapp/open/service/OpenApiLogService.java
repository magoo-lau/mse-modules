package vip.mseapp.open.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.open.entity.OpenApiLog;
import vip.mseapp.open.mapper.OpenApiLogMapper;

import java.time.LocalDateTime;

/**
 * 开放 API 调用审计：异步落库（独立小线程池 + CallerRuns 背压，绝不丢日志也不阻塞调用线程）。
 * 落库随世界路由（一切皆租户）：异步线程租户上下文经 openApiLogExecutor 的 TaskDecorator
 * 捕获恢复 + 行上显式 tenantId 双保险锚定（runAs）；管理端检索按会话世界收口
 * （tenantId 由控制器解析——租户会话锁定本租户，平台会话跨租户 = 全局视图读共享主库，
 * 不含独立库租户专属库流水）。
 */
@Service
public class OpenApiLogService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiLogService.class);

    private final OpenApiLogMapper logMapper;

    public OpenApiLogService(OpenApiLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    @Async("openApiLogExecutor")
    public void recordAsync(OpenApiLog entry) {
        try {
            if (entry.getTenantId() == null) {
                // 未解析到应用的被拒调用（凭证缺失/无效即拦，无租户语境）：runIgnore 落内置共享库审计，
                // 避免 runAs(null) 落 primary 平台库（无 open_api_log 表）恒失败
                TenantContext.runIgnore(() -> logMapper.insert(entry));
            } else {
                TenantContext.runAs(entry.getTenantId(), () -> logMapper.insert(entry));
            }
        } catch (Exception e) {
            // 审计失败不影响调用方，但必须留痕排查
            log.warn("开放 API 审计日志落库失败: {}", entry, e);
        }
    }

    /** 管理端分页查询（tenantId 非空 = 本世界路由读取；空 = 平台会话全局视图，主库全局扫描） */
    public PageResult<OpenApiLog> page(long pageNum, long pageSize, Long tenantId, Long appId, String apiCode,
                                       Integer statusCode, LocalDateTime beginTime, LocalDateTime endTime) {
        LambdaQueryWrapper<OpenApiLog> wrapper = new LambdaQueryWrapper<OpenApiLog>()
                .eq(tenantId != null, OpenApiLog::getTenantId, tenantId)
                .eq(appId != null, OpenApiLog::getAppId, appId)
                .like(apiCode != null && !apiCode.isBlank(), OpenApiLog::getApiCode, apiCode)
                .eq(statusCode != null, OpenApiLog::getStatusCode, statusCode)
                .ge(beginTime != null, OpenApiLog::getCreateTime, beginTime)
                .le(endTime != null, OpenApiLog::getCreateTime, endTime)
                .orderByDesc(OpenApiLog::getId);
        Page<OpenApiLog> page = tenantId != null
                ? TenantContext.runAs(tenantId, () -> logMapper.selectPage(new Page<>(pageNum, pageSize), wrapper))
                : TenantContext.runIgnore(() -> logMapper.selectPage(new Page<>(pageNum, pageSize), wrapper));
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }
}
