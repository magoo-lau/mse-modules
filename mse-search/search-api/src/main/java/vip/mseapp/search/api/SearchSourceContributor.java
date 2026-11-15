package vip.mseapp.search.api;

import java.util.List;

/**
 * 可搜索源贡献者（业务积木侧 SPI）：声明"我有什么可搜索的数据"。
 *
 * <p>业务积木实现本接口并注册为 Spring Bean（建议在业务自动装配类里
 * {@code @Bean + @ConditionalOnMissingBean(name="xxxSearchSourceContributor")} 声明，
 * 按名判重防多积木互相让位），search-api 的推送器会在应用就绪后把全部 Contributor 的源描述
 * 推送至搜索引擎目录，并周期重推自愈——业务新增可搜索数据只动自己的工程。
 *
 * <p>配套要求：每个源需在本积木内提供 {@link RemoteSearchDataProvider} 实现（按
 * providerGroup 寻址）；可选经 MQ 发送 {@link SearchIndexTopics} 索引事件获得实时增量。
 */
public interface SearchSourceContributor {

    /** 本积木声明的可搜索源清单 */
    List<SearchSourceDescriptor> sources();
}
