package vip.mseapp.search.rpc;

import org.springframework.stereotype.Component;
import vip.mseapp.core.annotation.RpcService;
import vip.mseapp.search.api.RemoteSearchSourceService;
import vip.mseapp.search.api.SearchSourceDescriptor;
import vip.mseapp.search.tenant.SearchSourceFleetSeeder;

import java.util.List;

/**
 * 可搜索源目录契约实现（search-api 推送器的注册端点）：把描述符异步扇出播种至
 * 「平台 + 全部租户」各自目录（幂等 upsert，扫描参数与启停状态等管理端资产不被覆盖）；
 * 单上下文失败由推送器周期重推自愈。实例由 SearchServiceAutoConfiguration
 * 以 {@code @Bean + @ConditionalOnMissingBean} 注册（接入方可整体替换）。
 * 管理端「手工登记」不经此处——直接写当前会话归属目录，不扇出。
 */
@RpcService(RemoteSearchSourceService.class)
public class RemoteSearchSourceServiceImpl implements RemoteSearchSourceService {

    private final SearchSourceFleetSeeder fleetSeeder;

    public RemoteSearchSourceServiceImpl(SearchSourceFleetSeeder fleetSeeder) {
        this.fleetSeeder = fleetSeeder;
    }

    @Override
    public void register(List<SearchSourceDescriptor> descriptors) {
        fleetSeeder.seedAsync(descriptors);
    }
}
