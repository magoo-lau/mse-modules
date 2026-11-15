package vip.mseapp.open.api;

import java.util.List;

/**
 * 开放能力贡献者（业务积木侧 SPI）：声明"我开放什么"。
 *
 * <p>业务积木实现本接口并注册为 Spring Bean（建议在业务自动装配类里
 * {@code @Bean + @ConditionalOnMissingBean} 声明，保持可替换语义），open-api 的自动装配
 * 会在应用就绪后把全部 Contributor 的描述推送至开放平台目录，并周期重推自愈——
 * 业务新增开放能力只动自己的工程。
 *
 * <p>约束：code 全局唯一（open:&lt;biz&gt;:...）；path 以 {@code /open/api/} 开头且含业务
 * 前缀段（如 /open/api/oa/...）；target 指向的契约方法签名必须为
 * {@code String 方法名(String requestJson)}（见 {@link OpenApiDescriptor}）。
 */
public interface OpenApiContributor {

    /** 本积木对外开放的 API 描述清单 */
    List<OpenApiDescriptor> descriptors();
}
