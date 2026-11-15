package vip.mseapp.search.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.search.entity.SearchTask;
import vip.mseapp.search.service.SearchTaskService;

/**
 * 搜索引擎任务（平台端）：全量重建 / 索引优化 / 立即扫描的触发与进度查看。
 */
@RestController
@RequestMapping("/search/task")
public class SearchTaskController {

    private final SearchTaskService taskService;

    public SearchTaskController(SearchTaskService taskService) {
        this.taskService = taskService;
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:task:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:task:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/page")
    public Result<PageResult<SearchTask>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestParam(required = false) Integer status,
                                               @RequestParam(required = false) String taskType) {
        Page<SearchTask> page = taskService.page(pageNum, pageSize, status, taskType);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:task:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:task:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping
    public Result<Long> create(@RequestBody TaskRequest request) {
        return Result.ok(taskService.create(request.taskType(), request.sourceCode(),
                Boolean.TRUE.equals(request.idleOnly())));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:task:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:task:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping("/{id}/run-now")
    public Result<Void> runNow(@PathVariable Long id) {
        taskService.runNow(id);
        return Result.ok();
    }

    public record TaskRequest(String taskType, String sourceCode, Boolean idleOnly) {
    }
}
