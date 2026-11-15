package vip.mseapp.im.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImInstanceConfig;
import vip.mseapp.im.mapper.ImInstanceConfigMapper;

/**
 * 租户 mse Web 端基址（webBaseUrl）解析：通知卡片/工作台跳转等对基座页面拼绝对地址的
 * 统一出口。解析链：租户台账行 im_instance_config.web_base_url > 全局配置
 * {@code mse.im.web-base-url} > 内置缺省。
 */
@Component
public class ImWebBaseResolver {

    private static final String DEFAULT_WEB_BASE = "http://localhost:3000";

    private final MseImProperties properties;
    private final ImInstanceConfigMapper instanceMapper;

    public ImWebBaseResolver(MseImProperties properties, ImInstanceConfigMapper instanceMapper) {
        this.properties = properties;
        this.instanceMapper = instanceMapper;
    }

    /** 仅接受 http(s) 绝对地址，错值落缺省防打不开 */
    public String resolve(Long tenantId) {
        String fallback = properties.getWebBaseUrl() == null || properties.getWebBaseUrl().isBlank()
                ? DEFAULT_WEB_BASE : properties.getWebBaseUrl().trim();
        ImInstanceConfig row = instanceMapper.selectOne(new LambdaQueryWrapper<ImInstanceConfig>()
                .eq(ImInstanceConfig::getTenantId, tenantId)
                .last("limit 1"));
        if (row == null || row.getWebBaseUrl() == null || row.getWebBaseUrl().isBlank()) {
            return fallback;
        }
        String candidate = row.getWebBaseUrl().trim();
        return candidate.matches("^https?://\\S+$") ? candidate : fallback;
    }
}
