package vip.mseapp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.data.config.DataCrypt;
import vip.mseapp.data.entity.DatApi;
import vip.mseapp.data.entity.DatApiApp;
import vip.mseapp.data.entity.DatApiGrant;
import vip.mseapp.data.mapper.DatApiAppMapper;
import vip.mseapp.data.mapper.DatApiGrantMapper;
import vip.mseapp.data.mapper.DatApiMapper;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 调用方应用管理：appKey/appSecret 生命周期与 API 授权。
 *
 * <p>secret 明文仅在创建/重置响应中出现一次，落库为 AES-GCM 密文。
 */
@Service
public class ApiAppService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatApiAppMapper appMapper;
    private final DatApiGrantMapper grantMapper;
    private final DatApiMapper apiMapper;
    private final DataCrypt crypt;

    public ApiAppService(DatApiAppMapper appMapper, DatApiGrantMapper grantMapper,
                         DatApiMapper apiMapper, DataCrypt crypt) {
        this.appMapper = appMapper;
        this.grantMapper = grantMapper;
        this.apiMapper = apiMapper;
        this.crypt = crypt;
    }

    public PageResult<DatApiApp> page(long pageNum, long pageSize, String keyword, Integer status) {
        LambdaQueryWrapper<DatApiApp> wrapper = new LambdaQueryWrapper<DatApiApp>()
                .like(keyword != null && !keyword.isBlank(), DatApiApp::getName, keyword)
                .eq(status != null, DatApiApp::getStatus, status)
                .orderByDesc(DatApiApp::getId);
        Page<DatApiApp> page = appMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        page.getRecords().forEach(app -> app.setAppSecret(null));
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public SecretView create(String name, String remark) {
        if (name == null || name.isBlank()) {
            throw new ServiceException(400, "应用名称不能为空");
        }
        DatApiApp app = new DatApiApp();
        app.setName(name);
        // appKey 内嵌世界段（dat-{tenantId}-{hex}）：无上下文的开放调用通道凭 key 即可定位应用所属世界
        Long world = vip.mseapp.core.context.TenantContext.getRequiredTenantId();
        app.setAppKey("dat-" + world + "-" + randomHex(8));
        String secret = randomHex(16);
        app.setAppSecret(crypt.encrypt(secret));
        app.setRemark(remark);
        appMapper.insert(app);
        return new SecretView(app.getId(), app.getName(), app.getAppKey(), secret);
    }

    /** 重置密钥：旧密钥立即失效，明文仅本次返回 */
    @Transactional(rollbackFor = Exception.class)
    public SecretView resetSecret(Long id) {
        DatApiApp app = getRaw(id);
        String secret = randomHex(16);
        app.setAppSecret(crypt.encrypt(secret));
        appMapper.updateById(app);
        return new SecretView(app.getId(), app.getName(), app.getAppKey(), secret);
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, boolean enable) {
        DatApiApp app = getRaw(id);
        app.setStatus(enable ? DatApiApp.STATUS_ENABLED : DatApiApp.STATUS_DISABLED);
        appMapper.updateById(app);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getRaw(id);
        grantMapper.delete(new LambdaQueryWrapper<DatApiGrant>().eq(DatApiGrant::getAppId, id));
        appMapper.deleteById(id);
    }

    // ---------------- 授权 ----------------

    /**
     * 批量授权（upsert）：未授权的新增，已授权的刷新过期时间（传 null 即恢复永久）。
     */
    @Transactional(rollbackFor = Exception.class)
    public GrantResult grant(Long appId, List<Long> apiIds, LocalDateTime expireTime) {
        DatApiApp app = getRaw(appId);
        List<Long> distinct = apiIds == null ? List.of()
                : apiIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (distinct.isEmpty()) {
            throw new ServiceException(400, "请选择要授权的 API");
        }
        List<DatApi> apis = apiMapper.selectBatchIds(distinct);
        if (apis.size() != distinct.size()) {
            throw new ServiceException(404, "部分 API 不存在");
        }
        for (DatApi api : apis) {
            if (app.getTenantId().longValue() != api.getTenantId().longValue()) {
                throw new ServiceException(400, "应用与 API「" + api.getName() + "」不属于同一租户");
            }
        }
        Map<Long, DatApiGrant> existing = grantMapper.selectList(new LambdaQueryWrapper<DatApiGrant>()
                        .eq(DatApiGrant::getAppId, appId).in(DatApiGrant::getApiId, distinct)).stream()
                .collect(Collectors.toMap(DatApiGrant::getApiId, g -> g));
        int added = 0;
        int updated = 0;
        for (Long apiId : distinct) {
            DatApiGrant grant = existing.get(apiId);
            if (grant == null) {
                grant = new DatApiGrant();
                grant.setAppId(appId);
                grant.setApiId(apiId);
                grant.setExpireTime(expireTime);
                grantMapper.insert(grant);
                added++;
            } else if (!Objects.equals(grant.getExpireTime(), expireTime)) {
                grant.setExpireTime(expireTime);
                grantMapper.updateById(grant);
                updated++;
            }
        }
        return new GrantResult(added, updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long appId, Long apiId) {
        grantMapper.delete(new LambdaQueryWrapper<DatApiGrant>()
                .eq(DatApiGrant::getAppId, appId).eq(DatApiGrant::getApiId, apiId));
    }

    /** 应用的授权清单（带 API 名称/路径便于前端展示） */
    public List<GrantView> grants(Long appId) {
        List<DatApiGrant> grants = grantMapper.selectList(new LambdaQueryWrapper<DatApiGrant>()
                .eq(DatApiGrant::getAppId, appId));
        return grants.stream().map(grant -> {
            DatApi api = apiMapper.selectById(grant.getApiId());
            return new GrantView(grant.getId(),
                    grant.getApiId(), api == null ? null : api.getName(),
                    api == null ? null : api.getPath(),
                    grant.getExpireTime());
        }).toList();
    }

    private DatApiApp getRaw(Long id) {
        DatApiApp app = appMapper.selectById(id);
        if (app == null) {
            throw new ServiceException(404, "应用不存在");
        }
        return app;
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    public record SecretView(Long id, String name, String appKey, String appSecret) {
    }

    public record GrantView(Long id, Long apiId, String apiName, String apiPath,
                            LocalDateTime expireTime) {
    }

    /** 批量授权结果：added 新授权数，updated 刷新过期时间的已授权数 */
    public record GrantResult(int added, int updated) {
    }
}
