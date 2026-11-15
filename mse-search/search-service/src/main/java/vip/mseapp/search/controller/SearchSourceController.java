package vip.mseapp.search.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.search.api.SearchFieldDescriptor;
import vip.mseapp.search.api.SearchSourceDescriptor;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.service.SearchFieldCatalogService;
import vip.mseapp.search.service.SearchFieldConfigService;
import vip.mseapp.search.service.SearchSourceService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可搜索源管理（租户端）：每租户一份目录（含平台 0）——自注册扇出播种的源目录查看/调参/
 * 启停 + 字段配置 + 手工建源（无业务接入时的调试通道，写入当前会话归属目录，不扇出）。
 */
@Validated
@RestController
@RequestMapping("/search/source")
public class SearchSourceController {

    private final SearchSourceService sourceService;
    private final SearchFieldConfigService fieldConfigService;

    public SearchSourceController(SearchSourceService sourceService, SearchFieldConfigService fieldConfigService) {
        this.sourceService = sourceService;
        this.fieldConfigService = fieldConfigService;
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:source:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:source:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/page")
    public Result<PageResult<SearchSource>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "10") long pageSize,
                                                 @RequestParam(required = false) String keyword) {
        Page<SearchSource> page = sourceService.page(pageNum, pageSize, keyword);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:source:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:source:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/{id}")
    public Result<SearchSource> get(@PathVariable Long id) {
        SearchSource source = sourceService.getById(id);
        if (source == null) {
            throw new ServiceException(404, "源不存在");
        }
        return Result.ok(source);
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:source:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:source:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping
    public Result<Void> save(@RequestBody SourceRequest request) {
        sourceService.updateScanConfig(request.id(), request.scanCron(), request.batchSize(),
                request.shardCount(), request.dataScopeEnabled(), request.permissionCode(),
                request.visibleRoles(), request.domain());
        return Result.ok();
    }

    /** cron 解析与最近 N 次执行时间预览：与调度器同用 Spring CronExpression，非法时返回 valid=false 走结构化结果（前端内联提示，不抛 400） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:source:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:source:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/cron-preview")
    public Result<CronPreview> cronPreview(@RequestParam String cron) {
        String expression = cron == null ? "" : cron.trim();
        if (expression.isEmpty()) {
            return Result.ok(new CronPreview(false, "cron 表达式为空", List.of()));
        }
        CronExpression parsed;
        try {
            parsed = CronExpression.parse(expression);
        } catch (IllegalArgumentException e) {
            return Result.ok(new CronPreview(false, "表达式非法: " + e.getMessage(), List.of()));
        }
        List<String> times = new ArrayList<>(10);
        LocalDateTime time = LocalDateTime.now().withNano(0);
        while (times.size() < 10) {
            time = parsed.next(time);
            if (time == null) {
                break;
            }
            times.add(time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        return Result.ok(new CronPreview(true, null, times));
    }

    /** 手工建源/登记（调试通道：复用自注册 upsert 语义；写入当前会话归属目录，不扇出） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:source:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:source:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Validated DescriptorRequest request) {
        SearchSourceDescriptor descriptor = new SearchSourceDescriptor();
        descriptor.setSourceCode(request.sourceCode());
        descriptor.setSourceName(request.sourceName());
        descriptor.setDomain(request.domain());
        descriptor.setProviderGroup(request.providerGroup());
        descriptor.setJumpUrlTemplate(request.jumpUrlTemplate());
        descriptor.setPermissionCode(request.permissionCode());
        descriptor.setDataScopeEnabled(request.dataScopeEnabled());
        descriptor.setScanCron(request.scanCron());
        descriptor.setBatchSize(request.batchSize());
        descriptor.setShardCount(request.shardCount());
        descriptor.setDescription("管理端手工登记");
        sourceService.registerDescriptors(List.of(descriptor));
        return Result.ok();
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:source:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:source:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam int status) {
        sourceService.updateStatus(id, status);
        return Result.ok();
    }

    /** 源字段配置查看（平台级；customized=true 表示管理端已定制，编码默认推送不再覆盖） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:source:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:source:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/{id}/fields")
    public Result<FieldsView> getFields(@PathVariable Long id) {
        SearchSource source = requireSource(id);
        return Result.ok(new FieldsView(fieldConfigService.parsePlatform(source.getFieldsJson()),
                source.getFieldsCustomized() != null && source.getFieldsCustomized() == 1));
    }

    /**
     * 源字段配置保存（校验 + 标记已定制）。mapping 影响面在返回中给出：字段增删或类型变更时
     * mappingChanged=true，存量文档需创建 FULL_REBUILD 任务补齐。
     */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:source:field", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:source:field", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PutMapping("/{id}/fields")
    public Result<FieldSaveResult> saveFields(@PathVariable Long id,
                                              @RequestBody List<SearchFieldDescriptor> fields) {
        SearchSource before = requireSource(id);
        Map<String, String> beforeTypes = fieldTypes(fieldConfigService.parsePlatform(before.getFieldsJson()));
        SearchSource saved = sourceService.saveFields(id, fields);
        boolean mappingChanged = !beforeTypes.equals(
                fieldTypes(fieldConfigService.parsePlatform(saved.getFieldsJson())));
        return Result.ok(new FieldSaveResult(mappingChanged));
    }

    /** 业务字段目录（按需实时拉取业务 Provider，标注平台收录状态；目录不可用时降级返回原因） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:source:field", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:source:field", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/{id}/catalog")
    public Result<SearchFieldCatalogService.CatalogView> catalog(@PathVariable Long id) {
        return Result.ok(sourceService.platformCatalog(id));
    }

    private SearchSource requireSource(Long id) {
        SearchSource source = sourceService.getById(id);
        if (source == null) {
            throw new ServiceException(404, "源不存在");
        }
        return source;
    }

    private static Map<String, String> fieldTypes(List<SearchFieldDescriptor> fields) {
        Map<String, String> types = new LinkedHashMap<>();
        fields.forEach(f -> types.put(f.getField(), f.getType()));
        return types;
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:source:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:source:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        throw new ServiceException(400, "自注册源由业务推送管理，请停用而非删除；手工源请走业务侧 unregister");
    }

    /** 字段配置视图：配置项 + 是否已管理端定制 */
    public record FieldsView(List<SearchFieldDescriptor> fields, boolean customized) {
    }

    /** 字段保存结果：mappingChanged=true 时前端提示创建全量重建任务 */
    public record FieldSaveResult(boolean mappingChanged) {
    }

    public record SourceRequest(Long id, String scanCron, Integer batchSize, Integer shardCount,
                                Integer dataScopeEnabled, String permissionCode, List<String> visibleRoles,
                                String domain) {
    }

    public record DescriptorRequest(String sourceCode, String sourceName, String domain, String providerGroup,
                                    String jumpUrlTemplate, String permissionCode, Boolean dataScopeEnabled,
                                    String scanCron, Integer batchSize, Integer shardCount) {
    }

    public record CronPreview(boolean valid, String message, List<String> nextTimes) {
    }
}
