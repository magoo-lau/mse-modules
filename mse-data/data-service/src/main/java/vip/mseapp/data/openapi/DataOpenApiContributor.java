package vip.mseapp.data.openapi;

import vip.mseapp.data.api.RemoteDataApiService;
import vip.mseapp.open.api.OpenApiContributor;
import vip.mseapp.open.api.OpenApiDescriptor;

import java.util.List;

/**
 * 数据中台开放能力声明（开放平台自注册：应用就绪后由 open-api 推送器推送至 mse-open 目录）。
 */
public class DataOpenApiContributor implements OpenApiContributor {

    @Override
    public List<OpenApiDescriptor> descriptors() {
        OpenApiDescriptor item = new OpenApiDescriptor();
        item.setCode("open:data:api-invoke");
        item.setName("调用数据 API");
        item.setHttpMethod("POST");
        item.setPath("/open/api/data/api/invoke");
        item.setTarget(RemoteDataApiService.class.getName() + "#invoke");
        item.setDescription("body: apiPath/params/pageNum/pageSize；调用管理端已审批发布的查询 API"
                + "（受控通道：SQL 防护+租户谓词+行数上限）");
        return List.of(item);
    }
}
