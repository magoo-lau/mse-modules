package vip.mseapp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.ai.entity.AiUsageLog;
import vip.mseapp.ai.mapper.AiUsageLogMapper;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;

import java.util.List;
import java.util.Map;

/**
 * AI 用量统计：用量流水由 UsageRecorder 以显式 tenantId 随世界路由落库（一切皆租户：独立库租户
 * 落专属库、共享租户落绑定共享库、平台世界 0 落平台库/降级主库），读取侧同口径——本世界读取
 * runAs 随世界路由 + 显式 tenant_id 条件（租户侧看本租户，平台侧锁定视图看指定租户）。
 * 平台会话的「全局视图」是跨租户全局扫描（合法 runIgnore 场景）：读共享主库聚合
 * （含共享租户 + 平台行；独立库租户流水驻专属库，不进全局汇总——跨库扇出聚合见 README 留档）。
 * 「平台用量」页面已转租户拥有：租户会话访问 /platform/* 时域锁定本租户（请求 tenantId 被忽略）。
 */
@RestController
@RequestMapping("/ai/usage")
public class AiUsageController {

    private final AiUsageLogMapper usageLogMapper;

    public AiUsageController(AiUsageLogMapper usageLogMapper) {
        this.usageLogMapper = usageLogMapper;
    }

    /** 租户侧流水分页（本世界路由，与写侧同口径） */
    @SaCheckPermission(value = "ai:usage:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<AiUsageLog>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "20") long pageSize,
                                               @RequestParam(required = false) String agentCode,
                                               @RequestParam(required = false) String channel) {
        Long world = TenantContext.getRequiredTenantId();
        LambdaQueryWrapper<AiUsageLog> wrapper = new LambdaQueryWrapper<AiUsageLog>()
                .eq(AiUsageLog::getTenantId, world)
                .orderByDesc(AiUsageLog::getId);
        if (agentCode != null && !agentCode.isBlank()) {
            wrapper.eq(AiUsageLog::getAgentCode, agentCode);
        }
        if (channel != null && !channel.isBlank()) {
            wrapper.eq(AiUsageLog::getChannel, channel);
        }
        Page<AiUsageLog> page = TenantContext.runAs(world, () ->
                usageLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper));
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    /** 租户侧按日汇总（近 N 天） */
    @SaCheckPermission(value = "ai:usage:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/summary")
    public Result<List<Map<String, Object>>> summary(@RequestParam(defaultValue = "14") int days) {
        Long world = TenantContext.getRequiredTenantId();
        return Result.ok(TenantContext.runAs(world, () -> summaryByDay(world, days)));
    }

    /** 流水分页（跨租户视角）：租户会话锁定本租户，平台会话可指定租户/查全量 */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:usage:platform", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:usage:platform", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/platform/page")
    public Result<PageResult<AiUsageLog>> platformPage(@RequestParam(defaultValue = "1") long pageNum,
                                                       @RequestParam(defaultValue = "20") long pageSize,
                                                       @RequestParam(required = false) Long tenantId) {
        Long locked = lockedTenantId();
        boolean tenantSession = locked != null;
        Long effective = tenantSession ? locked : tenantId;
        if (tenantSession) {
            // 租户会话：本世界路由读取（与写侧同口径）
            return Result.ok(TenantContext.runAs(locked, () ->
                    pageOf(effective, pageNum, pageSize)));
        }
        // 平台会话全局视图：跨租户全局扫描（runIgnore 读共享主库，不含独立库租户专属库流水）
        return Result.ok(TenantContext.runIgnore(() ->
                pageOf(effective, pageNum, pageSize)));
    }

    private PageResult<AiUsageLog> pageOf(Long effective, long pageNum, long pageSize) {
        LambdaQueryWrapper<AiUsageLog> wrapper = new LambdaQueryWrapper<AiUsageLog>()
                .orderByDesc(AiUsageLog::getId);
        if (effective != null) {
            wrapper.eq(AiUsageLog::getTenantId, effective);
        }
        Page<AiUsageLog> page = usageLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    /** 按日汇总（跨租户视角）：租户会话锁定本租户（本世界路由），平台会话全局或指定租户（主库全局扫描） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:usage:platform", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:usage:platform", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/platform/summary")
    public Result<List<Map<String, Object>>> platformSummary(@RequestParam(defaultValue = "14") int days,
                                                             @RequestParam(required = false) Long tenantId) {
        Long locked = lockedTenantId();
        Long effective = locked != null ? locked : tenantId;
        if (locked != null) {
            return Result.ok(TenantContext.runAs(locked, () -> summaryByDay(effective, days)));
        }
        return Result.ok(TenantContext.runIgnore(() -> summaryByDay(effective, days)));
    }

    /** 租户会话返回会话租户（防伪造 tenantId 越权），平台会话返回 null（不锁定） */
    private static Long lockedTenantId() {
        Long ctx = TenantContext.getTenantId();
        return (ctx != null && ctx != 0L) ? ctx : null;
    }

    private List<Map<String, Object>> summaryByDay(Long tenantId, int days) {
        // CAST AS DATE / COALESCE 为三方言（MySQL/达梦/金仓）通用写法
        QueryWrapper<AiUsageLog> wrapper = new QueryWrapper<AiUsageLog>()
                .select("CAST(create_time AS DATE) AS day",
                        "COUNT(*) AS calls",
                        "COALESCE(SUM(total_tokens),0) AS totalTokens",
                        "COALESCE(SUM(prompt_tokens),0) AS promptTokens",
                        "COALESCE(SUM(completion_tokens),0) AS completionTokens",
                        "SUM(CASE WHEN status <> 0 THEN 1 ELSE 0 END) AS failedCalls")
                .groupBy("day")
                .orderByDesc("day")
                .last("LIMIT " + Math.max(1, Math.min(days, 90)));
        if (tenantId != null) {
            wrapper.eq("tenant_id", tenantId);
        }
        return usageLogMapper.selectMaps(wrapper);
    }
}
