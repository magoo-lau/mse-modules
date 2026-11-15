package vip.mseapp.data.rpc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.annotation.RpcService;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.data.api.RemoteDataApiService;
import vip.mseapp.data.api.dto.DataApiInvokeRequest;
import vip.mseapp.data.api.dto.DataApiInvokeResult;
import vip.mseapp.data.entity.DatApi;
import vip.mseapp.data.mapper.DatApiMapper;
import vip.mseapp.data.open.ApiQueryExecutor;

/**
 * 数据服务开放契约实现（受控执行通道）。
 *
 * <p>与 data 自持的 /openapi/data 签名通道并存：本实现面向开放平台统一窗口——凭证/授权/
 * 限流/审计已由 mse-open 鉴权链完成，这里只按已发布 API 复用 {@link ApiQueryExecutor}
 * 执行（SQL 防护 + 租户谓词注入 + 行数/超时上限自动生效，租户上下文来自调用方透传）。
 * 装配：由 DataServiceAutoConfiguration @Bean 注册（可声明同契约 Bean 整体替换）。
 */
@RpcService(RemoteDataApiService.class)
public class RemoteDataApiServiceImpl implements RemoteDataApiService {

    private final DatApiMapper apiMapper;
    private final ApiQueryExecutor executor;
    private final JsonMapper objectMapper;

    public RemoteDataApiServiceImpl(DatApiMapper apiMapper, ApiQueryExecutor executor,
                                    JsonMapper objectMapper) {
        this.apiMapper = apiMapper;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String invoke(String requestJson) {
        DataApiInvokeRequest request = parse(requestJson);
        if (request.getApiPath() == null || request.getApiPath().isBlank()) {
            throw new ServiceException(400, "apiPath 不能为空");
        }
        DatApi api = apiMapper.selectOne(new LambdaQueryWrapper<DatApi>()
                .eq(DatApi::getPath, request.getApiPath()));
        if (api == null) {
            throw new ServiceException(404, "API 不存在：" + request.getApiPath());
        }
        if (api.getStatus() == null || api.getStatus() != DatApi.STATUS_PUBLISHED) {
            throw new ServiceException(400, "API 未发布或已下线：" + request.getApiPath());
        }
        boolean paged = request.getPageNum() != null || request.getPageSize() != null;
        Integer pageNum = request.getPageNum() == null ? null : Math.max(request.getPageNum(), 1);
        Integer pageSize = request.getPageSize() == null ? null : Math.min(Math.max(request.getPageSize(), 1), 200);
        ApiQueryExecutor.QueryResult result = executor.execute(api,
                request.getParams() == null ? java.util.Map.of() : request.getParams(),
                paged, pageNum, pageSize, false);
        DataApiInvokeResult payload = new DataApiInvokeResult();
        payload.setColumns(result.columns());
        payload.setRows(result.rows());
        payload.setHasMore(result.hasMore());
        payload.setCostMs(result.costMs());
        return toJson(payload);
    }

    private DataApiInvokeRequest parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ServiceException(400, "请求体不能为空");
        }
        try {
            return objectMapper.readValue(json, DataApiInvokeRequest.class);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(400, "请求 JSON 解析失败: " + e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ServiceException(500, "响应序列化失败: " + e.getMessage());
        }
    }
}
