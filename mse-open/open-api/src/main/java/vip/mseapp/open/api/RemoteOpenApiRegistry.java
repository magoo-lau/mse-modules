package vip.mseapp.open.api;

import java.util.List;

/**
 * 开放 API 目录注册契约（mse-open 实现，业务积木消费）。
 *
 * <p>业务侧由 open-api 的自动装配自动推送（见 support 包），无需手工调用；也可经
 * {@code @RpcReference} 注入本接口自行触发（如配置热变更后立即重注册）。
 * 注册为幂等 upsert：按 code 新增或更新定义字段（name/path/httpMethod/target/remark），
 * 管理端启停状态（status）与授权关系不被覆盖。
 */
public interface RemoteOpenApiRegistry {

    /**
     * 批量注册开放 API 描述。
     *
     * @param descriptors 描述清单（逐条独立校验：不合法条目跳过并告警，不拖垮整批）
     */
    void register(List<OpenApiDescriptor> descriptors);
}
