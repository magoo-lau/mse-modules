package vip.mseapp.open.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import vip.mseapp.api.tenant.RemoteTenantService;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.context.UserContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.open.api.OpenAppBrief;
import vip.mseapp.open.config.OpenCrypt;
import vip.mseapp.open.entity.OpenApiDef;
import vip.mseapp.open.entity.OpenApp;
import vip.mseapp.open.entity.OpenAppApi;
import vip.mseapp.open.mapper.OpenApiMapper;
import vip.mseapp.open.mapper.OpenAppApiMapper;
import vip.mseapp.open.mapper.OpenAppMapper;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * 接入应用管理：注册/编辑/启停/重置密钥/API 授权。
 *
 * <p>凭证世界化（一切皆租户）：app_key 格式 {@code mse-{tenantId}-{hex}}（自描述世界段，
 * 无上下文的换凭证通道凭 key 即可定位应用所属世界）；应用行随绑定租户世界路由——
 * 独立库租户落专属库、共享租户落绑定共享库、平台域应用（tenantId=0）落平台库/降级主库。
 * 会话归属由服务内守卫收口：租户会话只能查看/操作本租户应用（平台域应用仅平台会话可管理）；
 * 平台会话的跨租户全量列表/运维为全局视图（读共享主库，不含独立库租户专属库应用）。
 * 旧格式 app_key 行驻共享主库，换凭证通道主库兜底可继续使用（重置/重建即换发新格式）。
 *
 * <p>事务与世界路由红线：写世界在方法内显式锚定（runAs），单语句方法去事务化走语句级路由，
 * 多语句方法在 runAs 内经 TransactionTemplate 开事务（事务开启前必须已处于目标世界）。
 */
@Service
public class OpenAppService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OpenAppMapper appMapper;
    private final OpenAppApiMapper appApiMapper;
    private final OpenApiMapper apiMapper;
    private final OpenCrypt crypt;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /** 租户状态校验走基座契约（单体本地直调/微服务 Dubbo），不直接依赖 service-tenant 实现 */
    @RpcReference
    private RemoteTenantService remoteTenantService;

    public OpenAppService(OpenAppMapper appMapper, OpenAppApiMapper appApiMapper,
                          OpenApiMapper apiMapper, OpenCrypt crypt,
                          org.springframework.transaction.support.TransactionTemplate transactionTemplate) {
        this.appMapper = appMapper;
        this.appApiMapper = appApiMapper;
        this.apiMapper = apiMapper;
        this.crypt = crypt;
        this.transactionTemplate = transactionTemplate;
    }

    /** 创建结果：id + 一次性明文凭证 */
    public record AppCreated(Long id, String appKey, String appSecret) {
    }

    /** 租户下拉选项（仅暴露绑定场景所需字段） */
    public record TenantOption(Long id, String code, String name, Integer status) {
    }

    /** 租户下拉选项（应用绑定租户用；含停用租户，编辑回显不丢名） */
    public List<TenantOption> listTenantOptions() {
        return remoteTenantService.listTenants().stream()
                .map(t -> new TenantOption(t.getId(), t.getCode(), t.getName(), t.getStatus()))
                .toList();
    }

    /**
     * 租户名下应用简表（RPC 契约 {@code RemoteOpenAppService#listApps} 消费：
     * 业务积木免登授权入口的下拉候选，含停用行由调用方标注禁选）；密文不出域。
     */
    public List<OpenAppBrief> listBriefs(Long tenantId) {
        return TenantContext.runAs(tenantId, () -> appMapper.selectList(new LambdaQueryWrapper<OpenApp>()
                        .eq(OpenApp::getTenantId, tenantId).orderByDesc(OpenApp::getId)))
                .stream().map(OpenAppService::toBrief).toList();
    }

    /** 应用简表（RPC 契约 {@code RemoteOpenAppService#getApp} 消费：保存关联时的存在性校验）；不存在返回 null */
    public OpenAppBrief brief(Long appId) {
        if (appId == null) {
            return null;
        }
        // 调用方世界内定位（工作台等消费方的关联应用与调用方同世界）；平台会话（0）只见平台域应用
        Long world = TenantContext.getTenantId() == null ? 0L : TenantContext.getTenantId();
        OpenApp app = TenantContext.runAs(world, () -> appMapper.selectById(appId));
        return app == null ? null : toBrief(app);
    }

    private static OpenAppBrief toBrief(OpenApp app) {
        OpenAppBrief brief = new OpenAppBrief();
        brief.setId(app.getId());
        brief.setAppKey(app.getAppKey());
        brief.setName(app.getName());
        brief.setTenantId(app.getTenantId());
        brief.setStatus(app.getStatus());
        return brief;
    }

    public PageResult<OpenApp> page(long pageNum, long pageSize, String keyword, Integer status) {
        Long ctx = TenantContext.getTenantId();
        // 租户会话锁定本租户（本世界路由 + 显式归属过滤）；平台会话跨租户 = 全局视图
        //（runIgnore 读共享主库，不含独立库租户专属库应用——README 留档）
        boolean tenantSession = !UserContext.isPlatform() && ctx != null && ctx != 0;
        LambdaQueryWrapper<OpenApp> wrapper = new LambdaQueryWrapper<OpenApp>()
                .eq(tenantSession, OpenApp::getTenantId, ctx)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(OpenApp::getName, keyword).or().like(OpenApp::getAppKey, keyword))
                .eq(status != null, OpenApp::getStatus, status)
                .orderByDesc(OpenApp::getId);
        Page<OpenApp> page = tenantSession
                ? TenantContext.runAs(ctx, () -> appMapper.selectPage(new Page<>(pageNum, pageSize), wrapper))
                : TenantContext.runIgnore(() -> appMapper.selectPage(new Page<>(pageNum, pageSize), wrapper));
        // 密文不出域
        page.getRecords().forEach(app -> app.setAppSecret(null));
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    public OpenApp get(Long id) {
        OpenApp app = requireManaged(id);
        app.setAppSecret(null);
        return app;
    }

    public AppCreated create(String name, Long tenantId, Long rateLimitQps,
                             java.time.LocalDateTime expireTime, String remark,
                             String ipWhitelist, String domainWhitelist) {
        String trimmedName = requireText(name, "应用名称不能为空");
        if (tenantId == null || tenantId < 0) {
            throw new ServiceException(400, "绑定租户不合法");
        }
        validateTenant(tenantId);
        OpenApp app = new OpenApp();
        app.setName(trimmedName);
        app.setAppKey(generateAppKey(tenantId));
        app.setAppSecret(crypt.encrypt(generateSecret()));
        app.setTenantId(tenantId);
        applyOptional(app, rateLimitQps, expireTime, remark);
        applyWhitelists(app, ipWhitelist, domainWhitelist);
        app.setStatus(OpenApp.STATUS_ENABLED);
        // 单语句写：runAs 锚定绑定租户世界（一切皆租户），免事务走语句级路由
        writeInAppWorld(app, () -> appMapper.insert(app));
        // 明文仅此一次返回
        return new AppCreated(app.getId(), app.getAppKey(), crypt.decrypt(app.getAppSecret()));
    }

    public void update(Long id, String name, Long tenantId, Long rateLimitQps,
                       java.time.LocalDateTime expireTime, String remark,
                       Integer signRequired, String ipWhitelist, String domainWhitelist) {
        OpenApp app = requireManaged(id);
        if (name != null && !name.isBlank()) {
            app.setName(name.trim());
        }
        if (tenantId != null && !tenantId.equals(app.getTenantId())) {
            // 跨世界改绑涉及行跨库迁移，本期不支持（重建应用即换发新世界行）
            throw new ServiceException(400, "应用绑定租户与创建时不一致，跨世界改绑请删除后重建");
        }
        applyOptional(app, rateLimitQps, expireTime, remark);
        if (signRequired != null) {
            app.setSignRequired(signRequired == 1 ? 1 : 0);
        }
        applyWhitelists(app, ipWhitelist, domainWhitelist);
        writeInAppWorld(app, () -> appMapper.updateById(app));
    }

    public void changeStatus(Long id, boolean enable) {
        OpenApp app = requireManaged(id);
        app.setStatus(enable ? OpenApp.STATUS_ENABLED : OpenApp.STATUS_DISABLED);
        writeInAppWorld(app, () -> appMapper.updateById(app));
    }

    /** 重置密钥：明文仅此一次返回，旧密文作废 */
    public String resetSecret(Long id) {
        OpenApp app = requireManaged(id);
        String secret = generateSecret();
        app.setAppSecret(crypt.encrypt(secret));
        writeInAppWorld(app, () -> appMapper.updateById(app));
        return secret;
    }

    public void delete(Long id) {
        OpenApp app = requireManaged(id);
        writeInAppWorld(app, () -> transactionTemplate.executeWithoutResult(tx -> {
            appApiMapper.delete(new LambdaQueryWrapper<OpenAppApi>().eq(OpenAppApi::getAppId, id));
            appMapper.deleteById(id);
        }));
    }

    // -------------------- API 授权 --------------------

    /** 应用已授权的 API 编号集合 */
    public List<Long> listApiIds(Long appId) {
        OpenApp app = requireManaged(appId);
        return readInAppWorld(app, () -> appApiMapper.selectList(
                        new LambdaQueryWrapper<OpenAppApi>()
                                .eq(OpenAppApi::getAppId, appId)).stream()
                .map(OpenAppApi::getApiId)
                .toList());
    }

    /** 全量覆盖式授权；apiIds 非空时逐一校验存在且启用（事务在目标世界内开启） */
    public void grantApis(Long appId, List<Long> apiIds) {
        OpenApp app = requireManaged(appId);
        Long world = app.getTenantId();
        writeInAppWorld(app, () -> transactionTemplate.executeWithoutResult(tx -> {
            appApiMapper.delete(new LambdaQueryWrapper<OpenAppApi>().eq(OpenAppApi::getAppId, appId));
            if (apiIds == null) {
                return;
            }
            for (Long apiId : apiIds.stream().distinct().toList()) {
                OpenApiDef api = apiMapper.selectById(apiId);
                // 目录行世界归属校验：目录已每租户一份，授权只能挂应用归属世界的目录行（防挂他世界行 id 的脏授权）
                if (api == null || (world != 0L && !java.util.Objects.equals(api.getTenantId(), world))) {
                    throw new ServiceException(400, "API 不存在: " + apiId);
                }
                OpenAppApi grant = new OpenAppApi();
                grant.setAppId(appId);
                grant.setApiId(apiId);
                grant.setTenantId(world);
                appApiMapper.insert(grant);
            }
        }));
    }

    // -------------------- 内部 --------------------

    private void validateTenant(Long tenantId) {
        if (tenantId == 0) {
            return; // 平台域应用
        }
        remoteTenantService.validateTenant(tenantId);
    }

    private void applyOptional(OpenApp app, Long rateLimitQps,
                               java.time.LocalDateTime expireTime, String remark) {
        if (rateLimitQps != null) {
            if (rateLimitQps < 0) {
                throw new ServiceException(400, "限流 QPS 不能为负数");
            }
            app.setRateLimitQps(rateLimitQps);
        }
        app.setExpireTime(expireTime);
        app.setRemark(remark);
    }

    /**
     * 白名单三态落库：null = 不修改，空串 = 清空（不限制），其余整体 trim 后落库；
     * 域名白名单额外逐条格式校验（不合法 400），IP 白名单维持宽容语义（支持 CIDR，不做格式强校验）。
     */
    private void applyWhitelists(OpenApp app, String ipWhitelist, String domainWhitelist) {
        if (ipWhitelist != null) {
            app.setIpWhitelist(ipWhitelist.isBlank() ? "" : ipWhitelist.trim());
        }
        if (domainWhitelist != null) {
            app.setDomainWhitelist(normalizeDomainWhitelist(domainWhitelist));
        }
    }

    /** 域名白名单条目：裸域名（example.com）或子域通配（*.example.com），不带协议/端口/路径 */
    private static final java.util.regex.Pattern DOMAIN_ENTRY = java.util.regex.Pattern.compile(
            "^(\\*\\.)?([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$");

    /**
     * 域名白名单规范化：逗号/分号/换行分隔，逐条校验格式并指明第几条；空白输入返回 ""（= 不限制）。
     */
    private static String normalizeDomainWhitelist(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        int no = 0;
        for (String entry : value.split("[,;\\n]")) {
            String item = entry.trim();
            if (item.isEmpty()) {
                continue;
            }
            no++;
            if (!DOMAIN_ENTRY.matcher(item).matches()) {
                throw new ServiceException(400, "域名白名单第 " + no + " 条不合法（应为裸域名或 *.子域通配，"
                        + "如 example.com、*.api.example.com；不带协议/端口/路径）: " + item);
            }
        }
        return value;
    }

    private OpenApp requireApp(Long id) {
        Long ctx = TenantContext.getTenantId();
        // 租户会话：本世界直读
        if (!UserContext.isPlatform() && ctx != null && ctx != 0) {
            OpenApp app = TenantContext.runAs(ctx, () -> appMapper.selectById(id));
            if (app == null) {
                throw new ServiceException(404, "应用不存在");
            }
            return app;
        }
        // 平台会话：应用行随绑定租户落各租户世界（平台库无 open_app 表）——先查共享主库（runIgnore，
        // 覆盖共享租户与平台域应用），未命中再逐租户世界定位（独立库租户专属库亦可管理）
        OpenApp app = TenantContext.runIgnore(() -> appMapper.selectById(id));
        if (app == null) {
            for (TenantOption option : listTenantOptions()) {
                if (option.id() == null || option.id() == 0L) {
                    continue;
                }
                app = TenantContext.runAs(option.id(), () -> appMapper.selectById(id));
                if (app != null) {
                    break;
                }
            }
        }
        if (app == null) {
            throw new ServiceException(404, "应用不存在");
        }
        return app;
    }

    /** 应用行读世界锚定：租户应用=runAs(绑定租户)；平台域应用（0）/跨世界=共享主库（runIgnore，
     * 平台库无 open_app 表——「落平台库/降级主库」的降级分支） */
    private <T> T readInAppWorld(OpenApp app, java.util.function.Supplier<T> action) {
        if (app.getTenantId() != null && app.getTenantId() != 0) {
            return TenantContext.runAs(app.getTenantId(), action);
        }
        return TenantContext.runIgnore(action);
    }

    /** 应用行写世界锚定：租户应用=runAs(绑定租户)；平台域应用（0）=共享主库（runIgnore） */
    private void writeInAppWorld(OpenApp app, Runnable action) {
        if (app.getTenantId() != null && app.getTenantId() != 0) {
            TenantContext.runAs(app.getTenantId(), action);
        } else {
            TenantContext.runIgnore(action);
        }
    }

    /** 会话归属守卫：租户会话只能查看/操作本租户应用，平台域应用（tenantId=0）仅平台会话可管理 */
    private OpenApp requireManaged(Long id) {
        OpenApp app = requireApp(id);
        if (!UserContext.isPlatform() && !java.util.Objects.equals(app.getTenantId(), TenantContext.getTenantId())) {
            throw new ServiceException(403, "无权管理该应用");
        }
        return app;
    }

    /** app_key：内嵌世界段 mse-{tenantId}-{16hex}（自描述世界，无上下文换凭证通道可定位应用所属世界） */
    private static String generateAppKey(Long tenantId) {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "mse-" + tenantId + "-" + HexFormat.of().formatHex(bytes);
    }

    /** app_secret：40 位十六进制（160bit 随机） */
    private static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(400, message);
        }
        return value.trim();
    }
}
