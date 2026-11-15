package vip.mseapp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.core.context.UserContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.data.config.DataProperties;
import vip.mseapp.data.entity.DatApi;
import vip.mseapp.data.entity.DatApiApproval;
import vip.mseapp.data.mapper.DatApiApprovalMapper;
import vip.mseapp.data.mapper.DatApiMapper;
import vip.mseapp.data.open.ApiQueryExecutor;
import vip.mseapp.data.open.OpenApiService;
import vip.mseapp.ai.api.support.ApiSqlGuard;

import java.util.List;
import java.util.Map;

/**
 * 数据服务 API 管理：CRUD + 发布审批流（草稿 → 待审批 → 已发布/已驳回 → 下线）。
 *
 * <p>审批开关 mse.data.service.approval-enabled（默认开启）：关闭时保存即发布；
 * 开启时任何编辑都会把已发布/待审批的 API 打回草稿，重新走审批。
 */
@Service
public class ApiService {

    private static final String PATH_PATTERN = "^[a-z][a-z0-9-]{1,63}$";

    private final DatApiMapper apiMapper;
    private final DatApiApprovalMapper approvalMapper;
    private final OpenApiService openApiService;
    private final DataProperties properties;
    private final QueryAllowlistService allowlistService;

    public ApiService(DatApiMapper apiMapper, DatApiApprovalMapper approvalMapper,
                      OpenApiService openApiService, DataProperties properties,
                      QueryAllowlistService allowlistService) {
        this.apiMapper = apiMapper;
        this.approvalMapper = approvalMapper;
        this.openApiService = openApiService;
        this.properties = properties;
        this.allowlistService = allowlistService;
    }

    public PageResult<DatApi> page(long pageNum, long pageSize, String keyword, Integer status) {
        LambdaQueryWrapper<DatApi> wrapper = new LambdaQueryWrapper<DatApi>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(DatApi::getName, keyword).or().like(DatApi::getPath, keyword))
                .eq(status != null, DatApi::getStatus, status)
                .orderByDesc(DatApi::getId);
        Page<DatApi> page = apiMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    public DatApi get(Long id) {
        DatApi api = apiMapper.selectById(id);
        if (api == null) {
            throw new ServiceException(404, "API 不存在");
        }
        return api;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(DatApi api) {
        validate(api, true);
        api.setStatus(properties.getService().isApprovalEnabled()
                ? DatApi.STATUS_DRAFT : DatApi.STATUS_PUBLISHED);
        apiMapper.insert(api);
        record(api.getId(), api.getName(),
                properties.getService().isApprovalEnabled() ? DatApiApproval.ACTION_SUBMIT : DatApiApproval.ACTION_APPROVE,
                properties.getService().isApprovalEnabled() ? "创建草稿" : "审批关闭，保存即发布");
        return api.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(DatApi api) {
        DatApi existing = get(api.getId());
        validate(api, false);
        boolean approval = properties.getService().isApprovalEnabled();
        if (approval && (existing.getStatus() == DatApi.STATUS_PUBLISHED
                || existing.getStatus() == DatApi.STATUS_PENDING)) {
            // 已发布/审批中的 API 被修改 → 打回草稿重新走审批
            api.setStatus(DatApi.STATUS_DRAFT);
        }
        apiMapper.updateById(api);
        if (approval) {
            record(api.getId(), api.getName(), DatApiApproval.ACTION_SUBMIT, "内容修改，重新审批");
        }
    }

    /** 提交审批：草稿/已驳回 → 待审批 */
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        DatApi api = get(id);
        if (api.getStatus() != DatApi.STATUS_DRAFT && api.getStatus() != DatApi.STATUS_REJECTED) {
            throw new ServiceException(400, "仅草稿或已驳回的 API 可提交审批");
        }
        api.setStatus(DatApi.STATUS_PENDING);
        apiMapper.updateById(api);
        record(id, api.getName(), DatApiApproval.ACTION_SUBMIT, null);
    }

    /** 审批：待审批 → 已发布 / 已驳回 */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, boolean pass, String comment) {
        DatApi api = get(id);
        if (api.getStatus() != DatApi.STATUS_PENDING) {
            throw new ServiceException(400, "仅待审批状态的 API 可审批");
        }
        api.setStatus(pass ? DatApi.STATUS_PUBLISHED : DatApi.STATUS_REJECTED);
        apiMapper.updateById(api);
        record(id, api.getName(), pass ? DatApiApproval.ACTION_APPROVE : DatApiApproval.ACTION_REJECT, comment);
    }

    /** 下线：已发布 → 已下线（开放调用立即拒绝） */
    @Transactional(rollbackFor = Exception.class)
    public void offline(Long id, String comment) {
        DatApi api = get(id);
        if (api.getStatus() != DatApi.STATUS_PUBLISHED) {
            throw new ServiceException(400, "仅已发布的 API 可下线");
        }
        api.setStatus(DatApi.STATUS_OFFLINE);
        apiMapper.updateById(api);
        record(id, api.getName(), DatApiApproval.ACTION_OFFLINE, comment);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        get(id);
        apiMapper.deleteById(id);
    }

    /** 审批记录（倒序） */
    public List<DatApiApproval> approvals(Long id) {
        get(id);
        return approvalMapper.selectList(new LambdaQueryWrapper<DatApiApproval>()
                .eq(DatApiApproval::getApiId, id)
                .orderByDesc(DatApiApproval::getId));
    }

    private void record(Long apiId, String apiName, String action, String comment) {
        DatApiApproval approval = new DatApiApproval();
        approval.setApiId(apiId);
        approval.setApiName(apiName);
        approval.setAction(action);
        approval.setComment(comment);
        approval.setOperatorId(UserContext.getUserId());
        approval.setOperatorName(UserContext.getUsername());
        approvalMapper.insert(approval);
    }

    /** 试运行：跳过授权/限流，走当前登录租户，仅取 10 行 */
    public OpenApiService.OpenResponse preview(Long id, String paramsJson) {
        DatApi api = get(id);
        Map<String, Object> params = parseParams(paramsJson);
        return openApiService.preview(api, params);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build().readValue(paramsJson, Map.class);
        } catch (Exception e) {
            throw new ServiceException(400, "试运行参数 JSON 非法：" + e.getMessage());
        }
    }

    private void validate(DatApi api, boolean create) {
        if (api.getName() == null || api.getName().isBlank()) {
            throw new ServiceException(400, "API 名称不能为空");
        }
        if (api.getPath() == null || !api.getPath().matches(PATH_PATTERN)) {
            throw new ServiceException(400, "路径非法：小写字母开头，仅小写字母/数字/连字符，2-64 位");
        }
        if (create) {
            Long exists = apiMapper.selectCount(new LambdaQueryWrapper<DatApi>()
                    .eq(DatApi::getPath, api.getPath()));
            if (exists != null && exists > 0) {
                throw new ServiceException(400, "路径已存在：" + api.getPath());
            }
        }
        if (api.getDatasourceId() == null) {
            api.setDatasourceId(0L);
        }
        if (api.getSqlTemplate() == null || api.getSqlTemplate().isBlank()) {
            throw new ServiceException(400, "SQL 不能为空");
        }
        // 落库前过一遍防护（真实执行时还会再防护一次）；白名单与执行链路同源，中台库 API 可基于白名单表
        boolean internal = api.getDatasourceId() == 0;
        ApiSqlGuard.guard(api.getSqlTemplate(), internal,
                vip.mseapp.core.context.TenantContext.getRequiredTenantId(),
                internal ? allowlistService.enabledTableNames() : java.util.Set.of(), true);
        if (api.getParamsJson() != null && !api.getParamsJson().isBlank()) {
            ApiQueryExecutor.parseParamDefs(api.getParamsJson());
        }
        if (api.getPageEnabled() == null) {
            api.setPageEnabled(1);
        }
        if (api.getMaxRows() == null || api.getMaxRows() <= 0) {
            api.setMaxRows(1000);
        }
        if (api.getTimeoutMs() == null || api.getTimeoutMs() <= 0) {
            api.setTimeoutMs(10000);
        }
        if (api.getQpsLimit() == null || api.getQpsLimit() < 0) {
            api.setQpsLimit(0);
        }
    }
}
