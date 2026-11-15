package vip.mseapp.open.rpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.mseapp.core.annotation.RpcService;
import vip.mseapp.open.api.OpenApiDescriptor;
import vip.mseapp.open.api.RemoteOpenApiRegistry;
import vip.mseapp.open.service.OpenApiRegistryService;

import java.util.List;

/**
 * 目录注册契约实现（业务积木经 open-api 自动装配推送消费）。
 *
 * <p>注册写入全局表 open_api，与租户上下文无关；不合法条目逐条跳过（见
 * {@link OpenApiRegistryService#register}）。
 */
@RpcService(RemoteOpenApiRegistry.class)
public class RemoteOpenApiRegistryImpl implements RemoteOpenApiRegistry {

    private static final Logger log = LoggerFactory.getLogger(RemoteOpenApiRegistryImpl.class);

    private final OpenApiRegistryService registryService;

    public RemoteOpenApiRegistryImpl(OpenApiRegistryService registryService) {
        this.registryService = registryService;
    }

    @Override
    public void register(List<OpenApiDescriptor> descriptors) {
        int applied = registryService.register(descriptors);
        log.info("[open-api] 收到目录注册: 共 {} 条，落库 {} 条",
                descriptors == null ? 0 : descriptors.size(), applied);
    }
}
