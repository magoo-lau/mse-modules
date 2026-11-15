package vip.mseapp.search.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.mseapp.api.system.RemoteDeptService;
import vip.mseapp.api.system.RemoteUserService;
import vip.mseapp.api.system.dto.DeptDTO;
import vip.mseapp.api.system.dto.DeptTreeNodeDTO;
import vip.mseapp.api.system.dto.UserDTO;
import vip.mseapp.api.tenant.RemoteTenantService;
import vip.mseapp.api.tenant.dto.TenantDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.search.entity.SearchSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索调试选项（「搜索测试」页下拉数据源）：源 / 租户 / 用户 / 部门。
 *
 * <p>源为本积木自有数据；租户、用户、部门经基座契约获取——api-tenant 租户全量列表、
 * api-system 用户分页与部门树。用户与部门在目标租户上下文内查询
 * （{@link TenantContext#runAs} 指定租户，平台域传 0），与推送/查询的租户语义一致。
 *
 * <p>降级语义：契约缺失或调用异常时对应选项降级为空列表并告警——调试辅助能力，
 * 不影响搜索主链路。
 */
@Service
public class SearchDebugOptionService {

    private static final Logger log = LoggerFactory.getLogger(SearchDebugOptionService.class);

    /** 用户选项上限：调试页选择归属人够用，超限租户属异常规模 */
    private static final int USER_OPTION_LIMIT = 500;

    @RpcReference
    private RemoteUserService remoteUserService;

    @RpcReference
    private RemoteDeptService remoteDeptService;

    @RpcReference
    private RemoteTenantService remoteTenantService;

    private final SearchSourceService sourceService;

    public SearchDebugOptionService(SearchSourceService sourceService) {
        this.sourceService = sourceService;
    }

    public DebugOptions load(long tenantId) {
        List<SourceOption> sources = sourceService.listAll().stream()
                .map(s -> new SourceOption(s.getSourceCode(), s.getSourceName(), s.getStatus()))
                .toList();
        return new DebugOptions(sources, listTenants(), listUsers(tenantId), listDeptTree(tenantId));
    }

    /** 全量租户 + 平台域伪租户（0，与推送/查询的默认租户语义对齐） */
    private List<TenantOption> listTenants() {
        List<TenantOption> options = new ArrayList<>();
        options.add(new TenantOption(0L, "平台域", "platform", 0));
        try {
            for (TenantDTO t : remoteTenantService.listTenants()) {
                options.add(new TenantOption(t.getId(), t.getName(), t.getCode(), t.getStatus()));
            }
        } catch (Throwable e) {
            log.warn("[mse-search] 调试选项：租户列表获取失败，降级为空: {}", e.getMessage());
        }
        return options;
    }

    private List<UserOption> listUsers(long tenantId) {
        try {
            PageResult<UserDTO> page = TenantContext.runAs(tenantId,
                    () -> remoteUserService.pageUsers(1, USER_OPTION_LIMIT, null, null, null));
            return page.records().stream()
                    .map(u -> new UserOption(u.getId(), u.getUsername(), u.getNickname(), u.getDeptId(), u.getStatus()))
                    .toList();
        } catch (Throwable e) {
            log.warn("[mse-search] 调试选项：租户 {} 用户列表获取失败，降级为空: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    private List<DeptOptionNode> listDeptTree(long tenantId) {
        try {
            List<DeptTreeNodeDTO> tree = TenantContext.runAs(tenantId, () -> remoteDeptService.getDeptTree());
            return tree.stream().map(SearchDebugOptionService::toDeptNode).toList();
        } catch (Throwable e) {
            log.warn("[mse-search] 调试选项：租户 {} 部门树获取失败，降级为空: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    private static DeptOptionNode toDeptNode(DeptTreeNodeDTO node) {
        DeptDTO dept = node.getDept();
        List<DeptOptionNode> children = node.getChildren() == null ? List.of()
                : node.getChildren().stream().map(SearchDebugOptionService::toDeptNode).toList();
        return new DeptOptionNode(dept.getId(), dept.getName(), dept.getStatus(), children);
    }

    public record DebugOptions(List<SourceOption> sources, List<TenantOption> tenants,
                               List<UserOption> users, List<DeptOptionNode> deptTree) {
    }

    public record SourceOption(String sourceCode, String sourceName, Integer status) {
    }

    public record TenantOption(Long id, String name, String code, Integer status) {
    }

    public record UserOption(Long id, String username, String nickname, Long deptId, Integer status) {
    }

    public record DeptOptionNode(Long id, String name, Integer status, List<DeptOptionNode> children) {
    }
}
