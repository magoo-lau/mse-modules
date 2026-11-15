package vip.mseapp.data.api;

/**
 * 数据服务开放契约（data-service 实现）。
 *
 * <p>调用管理端已审批发布的查询 API（受控通道：SQL 防护 + 租户谓词注入 + 行数/超时上限）。
 * 机器语义约定见 {@code vip.mseapp.open.api.OpenApiDescriptor}：请求/返回均为 JSON 字符串，
 * 租户上下文由调用方注入、经 RPC 隐式透传，契约不携带 tenantId。
 */
public interface RemoteDataApiService {

    /**
     * 调用已发布的数据 API（按 data 服务内部 API path 定位）。
     *
     * @param requestJson DataApiInvokeRequest JSON：apiPath、params、可选 pageNum/pageSize
     * @return DataApiInvokeResult JSON：columns/rows/hasMore/costMs
     */
    String invoke(String requestJson);
}
