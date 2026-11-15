package vip.mseapp.im.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.config.ImCrypt;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImInstanceConfig;
import vip.mseapp.im.mapper.ImInstanceConfigMapper;

/**
 * 实例选址：按租户把请求路由到对应的 OpenIM 实例。
 *
 * <p>优先级：台账 DB 行（im_instance_config，租户行级隔离随插件生效）&gt;
 * {@code servers[tenantId]} 配置 &gt; {@code server} 默认段。
 * 台账行存在但停用 = 该租户 IM 显式关闭（不回落配置）；配置未命中或字段缺失
 * 在使用时抛业务异常（启动不阻断）。
 */
@Component
public class ImInstanceResolver {

    /** 台账来源：租户自助 */
    public static final String SOURCE_SELF_HOSTED = "TENANT_SELF_HOSTED";
    /** 台账来源：平台开通 */
    public static final String SOURCE_PLATFORM_HOSTED = "PLATFORM_HOSTED";

    private final MseImProperties properties;
    private final ImInstanceConfigMapper instanceMapper;
    private final ImCrypt imCrypt;

    public ImInstanceResolver(MseImProperties properties, ImInstanceConfigMapper instanceMapper, ImCrypt imCrypt) {
        this.properties = properties;
        this.instanceMapper = instanceMapper;
        this.imCrypt = imCrypt;
    }

    /**
     * 按租户选址。
     *
     * @throws ServiceException 总开关关闭 / 平台账号（tenantId=0）/ 台账行停用 / 实例未配置或缺 secret
     */
    public MseImProperties.Server resolve(Long tenantId) {
        if (!properties.isEnabled()) {
            throw new ServiceException(503, "IM 功能未开通");
        }
        if (tenantId == null || tenantId == 0L) {
            throw new ServiceException(403, "平台账号不参与 IM");
        }
        ImInstanceConfig row = instanceMapper.selectOne(new LambdaQueryWrapper<ImInstanceConfig>()
                .eq(ImInstanceConfig::getTenantId, tenantId)
                .last("limit 1"));
        if (row != null) {
            if (row.getStatus() == null || row.getStatus() != 0) {
                throw new ServiceException(503, "该租户 IM 已停用");
            }
            return toServer(row);
        }
        MseImProperties.Server server = properties.getServers().get(String.valueOf(tenantId));
        if (server == null) {
            server = properties.getServer();
        }
        if (server == null || isBlank(server.getApiUrl())) {
            throw new ServiceException(503, "当前租户未配置 IM 实例");
        }
        if (isBlank(server.getSecret())) {
            throw new ServiceException(503, "当前租户 IM 实例缺少 secret 配置");
        }
        return server;
    }

    /** 下发客户端的 apiAddr：公网坐标缺省回落内网值 */
    public String apiAddr(MseImProperties.Server server) {
        return firstNonBlank(server.getPublicApiUrl(), server.getApiUrl());
    }

    /** 下发客户端的 wsAddr：公网坐标 → 内网 ws → 内网 api */
    public String wsAddr(MseImProperties.Server server) {
        return firstNonBlank(server.getPublicWsUrl(), server.getWsUrl(), server.getApiUrl());
    }

    private MseImProperties.Server toServer(ImInstanceConfig row) {
        MseImProperties.Server server = new MseImProperties.Server();
        server.setApiUrl(row.getApiUrl());
        server.setWsUrl(row.getWsUrl());
        server.setPublicApiUrl(row.getPublicApiUrl());
        server.setPublicWsUrl(row.getPublicWsUrl());
        server.setSecret(imCrypt.decrypt(row.getSecretEnc()));
        server.setAdminUserId(row.getAdminUserId() == null || row.getAdminUserId().isBlank()
                ? "imAdmin" : row.getAdminUserId());
        return server;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        throw new ServiceException(503, "IM 实例地址未配置");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
