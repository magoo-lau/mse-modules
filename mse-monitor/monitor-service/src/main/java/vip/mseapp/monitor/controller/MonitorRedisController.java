package vip.mseapp.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.monitor.service.RedisCacheService;

import java.util.List;
import java.util.Map;

/**
 * Redis 缓存监控（双端）：目标清单按会话租户隔离；浏览与删键同属「缓存监控」页，
 * 删键走独立权限点 monitor:cache:delete。
 *
 * <p>双码必须 OR 放行（mode 缺省为 AND）：monitor:cache:query（4311）已随菜单合并下线删除，
 * 保留在列表里仅为历史兼容——AND 语义下无人可同时持有两码，租户端浏览接口会整体 403。
 */
@Validated
@RestController
@RequestMapping("/monitor/redis")
public class MonitorRedisController {

    private final RedisCacheService redisCacheService;

    public MonitorRedisController(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    /** 可浏览的 Redis 监控目标清单（含停用目标，前端标记即可） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = {"monitor:redis:query", "monitor:cache:query"}, type = MseConstants.LOGIN_TYPE_TENANT, mode = SaMode.OR),
            @SaCheckPermission(value = {"monitor:redis:query", "monitor:cache:query"}, type = MseConstants.LOGIN_TYPE_PLATFORM, mode = SaMode.OR)})
    @GetMapping("/targets")
    public Result<List<RedisCacheService.RedisTargetItem>> targets() {
        return Result.ok(redisCacheService.listTargets());
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = {"monitor:redis:query", "monitor:cache:query"}, type = MseConstants.LOGIN_TYPE_TENANT, mode = SaMode.OR),
            @SaCheckPermission(value = {"monitor:redis:query", "monitor:cache:query"}, type = MseConstants.LOGIN_TYPE_PLATFORM, mode = SaMode.OR)})
    @GetMapping("/{targetId}/info")
    public Result<Map<String, Object>> info(@PathVariable Long targetId,
                                            @RequestParam(defaultValue = "0") int db) {
        return Result.ok(redisCacheService.info(targetId, db));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = {"monitor:redis:query", "monitor:cache:query"}, type = MseConstants.LOGIN_TYPE_TENANT, mode = SaMode.OR),
            @SaCheckPermission(value = {"monitor:redis:query", "monitor:cache:query"}, type = MseConstants.LOGIN_TYPE_PLATFORM, mode = SaMode.OR)})
    @GetMapping("/{targetId}/keys")
    public Result<RedisCacheService.KeyPage> keys(@PathVariable Long targetId,
                                                  @RequestParam(defaultValue = "0") int db,
                                                  @RequestParam(required = false) String pattern,
                                                  @RequestParam(defaultValue = "1") long pageNum,
                                                  @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(redisCacheService.keys(targetId, db, pattern, pageNum, pageSize));
    }

    /** 键名全集（SCAN 收集上限内），前端按冒号分层构建键树（RDM 风格）；类型/TTL 点击叶子时查详情 */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = {"monitor:redis:query", "monitor:cache:query"}, type = MseConstants.LOGIN_TYPE_TENANT, mode = SaMode.OR),
            @SaCheckPermission(value = {"monitor:redis:query", "monitor:cache:query"}, type = MseConstants.LOGIN_TYPE_PLATFORM, mode = SaMode.OR)})
    @GetMapping("/{targetId}/key-names")
    public Result<RedisCacheService.KeyNames> keyNames(@PathVariable Long targetId,
                                                       @RequestParam(defaultValue = "0") int db,
                                                       @RequestParam(required = false) String pattern) {
        return Result.ok(redisCacheService.keyNames(targetId, db, pattern));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = {"monitor:redis:query", "monitor:cache:query"}, type = MseConstants.LOGIN_TYPE_TENANT, mode = SaMode.OR),
            @SaCheckPermission(value = {"monitor:redis:query", "monitor:cache:query"}, type = MseConstants.LOGIN_TYPE_PLATFORM, mode = SaMode.OR)})
    @GetMapping("/{targetId}/key")
    public Result<RedisCacheService.KeyDetail> keyDetail(@PathVariable Long targetId,
                                                         @RequestParam(defaultValue = "0") int db,
                                                         @RequestParam String name) {
        return Result.ok(redisCacheService.keyDetail(targetId, db, name));
    }

    /** 删键独立权限点（界面二次确认） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:cache:delete", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:cache:delete", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @DeleteMapping("/{targetId}/key")
    public Result<Long> deleteKey(@PathVariable Long targetId,
                                  @RequestParam(defaultValue = "0") int db,
                                  @RequestParam String name) {
        return Result.ok(redisCacheService.deleteKey(targetId, db, name));
    }
}
