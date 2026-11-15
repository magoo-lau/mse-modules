package vip.mseapp.open.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.open.api.OpenApiDescriptor;
import vip.mseapp.open.config.OpenProperties;
import vip.mseapp.open.entity.OpenApiDef;
import vip.mseapp.open.entity.OpenAppApi;
import vip.mseapp.open.mapper.OpenApiMapper;
import vip.mseapp.open.mapper.OpenAppApiMapper;
import vip.mseapp.open.security.OpenApiCatalogService;
import vip.mseapp.open.tenant.OpenTenantRegistry;
import vip.mseapp.redis.config.MseRedisAutoConfiguration.LockOps;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import vip.mseapp.core.concurrent.MseExecutors;

/**
 * 开放 API 目录注册服务（业务积木自注册的接收端）——目录真租户化：注册推送扇出播种到
 * 「平台 + 全部租户」各自目录（范式同 mse-search 源目录扇出播种）。
 *
 * <p>幂等 upsert：按 (tenant_id, code) 新增或更新定义字段（name/path/httpMethod/target/remark/source），
 * 各世界管理端启停状态与 open_app_api 授权关系不被覆盖——重推自愈无副作用。
 * 租户世界行 id 采用与每租户种子脚本 V20261010_03_open__tenant_seed.sql 一致的确定性副本 id
 * （见 {@link #tenantCopyId}）：两路谁先到都产出同一行 id，种子脚本按副本 id 判重才能命中跳过。
 * 逐条独立校验：不合法条目告警跳过，不拖垮整批（注册方笔误不影响其他 API 上线）。
 *
 * <p>多实例收敛：每个业务积木实例都会周期重推（内容相同），按条目业务段（open:&lt;biz&gt;:）
 * 分组做 tryEnter 窗口标记，同一业务一个窗口内只处理一次；窗口在推送接收时标记（快，无 DB），
 * 极端情况（扇出中失败）自愈延迟一个窗口；Redis 异常降级直收。
 *
 * <p>扇出在独立单线程执行器异步执行（不阻塞注册 RPC/推送线程）：逐归属世界
 * {@code runIgnore} upsert（与页面/分发/治理同口径——目录行驻业务主库，按 tenant_id
 * 世界条件隔离，不随租户切库：切库会写进独立库租户库而全部读者在业务库，且与按
 * (tenant_id, code, deleted) 的存在性检查分库产生撞唯一键），单世界失败只记日志不阻断
 * 其余，等下轮业务推送（应用就绪 + 周期重推自愈）覆盖补齐。逐条自动提交（副本 id 对齐的
 * 两条改写经 TransactionTemplate 同事务）、靠幂等 upsert 收敛。
 *
 * <p>退役自清理（prune）：推送器按容器合并该应用全部 Contributor 全量推送，条目业务段
 * （open:&lt;biz&gt;:）即收敛单元——同段内本次未出现的 {@code source=rpc} 行视为提供方已
 * 下线，随播种物理删除（含 open_app_api 授权引用，同事务；种子/内置行不触碰；uk 含
 * deleted 列故软删会撞键）。提供方下线能力后一轮重推即全网自愈移除。知悉项：整段全量
 * 退役（推送空清单）推送器不发起调用，需人工清理。
 */
@Service
public class OpenApiRegistryService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiRegistryService.class);

    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "ALL");

    /** 同业务推送收敛窗口：覆盖多实例成簇推送的时间差，自愈重推由下个窗口兜底 */
    private static final Duration RECV_WINDOW = Duration.ofSeconds(30);

    private final OpenApiMapper apiMapper;
    private final OpenAppApiMapper appApiMapper;
    private final OpenApiCatalogService catalog;
    private final OpenProperties properties;
    private final OpenTenantRegistry tenantRegistry;
    private final ObjectProvider<LockOps> lockOps;
    private final TransactionTemplate transactionTemplate;

    /** 目录扇出播种执行器（单线程串行，防止推送风暴下扇出叠加）。经 MseExecutors 工厂：有界 + wrap */
    private final ExecutorService fleetExecutor = MseExecutors.singleThread("open-api-fleet-seeder", 64);

    public OpenApiRegistryService(OpenApiMapper apiMapper, OpenAppApiMapper appApiMapper,
                                  OpenApiCatalogService catalog, OpenProperties properties,
                                  OpenTenantRegistry tenantRegistry, ObjectProvider<LockOps> lockOps,
                                  TransactionTemplate transactionTemplate) {
        this.apiMapper = apiMapper;
        this.appApiMapper = appApiMapper;
        this.catalog = catalog;
        this.properties = properties;
        this.tenantRegistry = tenantRegistry;
        this.lockOps = lockOps;
        this.transactionTemplate = transactionTemplate;
    }

    /** 批量注册（扇出播种到平台 + 全部租户目录）；返回校验通过的受理条数 */
    public int register(List<OpenApiDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return 0;
        }
        List<OpenApiDescriptor> accepted = accept(descriptors);
        if (!accepted.isEmpty()) {
            fleetExecutor.submit(() -> {
                try {
                    seedAll(accepted);
                } catch (Throwable e) {
                    log.warn("[mse-open] 目录扇出播种异常（等待下轮推送重试）: {}", e.getMessage());
                }
            });
        }
        return accepted.size();
    }

    /** 接收侧快处理：逐条校验 + 按业务段窗口收敛（无 DB，不阻塞推送线程） */
    private List<OpenApiDescriptor> accept(List<OpenApiDescriptor> descriptors) {
        LockOps ops = lockOps.getIfAvailable();
        List<OpenApiDescriptor> accepted = new ArrayList<>();
        for (Map.Entry<String, List<OpenApiDescriptor>> batch : groupByBiz(descriptors).entrySet()) {
            if (!acquireWindow(ops, batch.getKey())) {
                continue;
            }
            for (OpenApiDescriptor descriptor : batch.getValue()) {
                String error = validate(descriptor);
                if (error != null) {
                    log.warn("[open-api] 注册条目不合法，已跳过（code={}）: {}",
                            descriptor.getCode(), error);
                    continue;
                }
                accepted.add(descriptor);
            }
        }
        return accepted;
    }

    /** 扇出播种：平台 + 逐租户 runIgnore upsert（目录行驻业务主库）+ 同段退役行自清理；单世界失败不阻断其余 */
    private void seedAll(List<OpenApiDescriptor> descriptors) {
        Map<String, Set<String>> bizCodes = pushedCodesByBiz(descriptors);
        for (Long tenantId : tenantRegistry.contextTenantIds()) {
            try {
                int applied = TenantContext.runIgnore(() -> upsertAll(descriptors, tenantId));
                int pruned = TenantContext.runIgnore(() -> pruneStale(bizCodes, tenantId));
                if (applied > 0 || pruned > 0) {
                    catalog.evict();
                    log.info("[open-api] 目录播种完成: tenant={} 条目={} 退役清理={}", tenantId, applied, pruned);
                }
            } catch (Exception e) {
                log.warn("[open-api] 目录播种失败（等下轮推送自愈）: tenant={} err={}", tenantId, e.getMessage());
            }
        }
    }

    /** 条目业务段（open:&lt;biz&gt;:）→ 本次推送 code 全集（段即收敛单元，推送器按容器合并全量） */
    private static Map<String, Set<String>> pushedCodesByBiz(List<OpenApiDescriptor> descriptors) {
        Map<String, Set<String>> bizCodes = new LinkedHashMap<>();
        for (OpenApiDescriptor descriptor : descriptors) {
            String[] parts = descriptor.getCode().split(":");
            bizCodes.computeIfAbsent(parts.length == 3 ? parts[1] : "", k -> new HashSet<>())
                    .add(descriptor.getCode());
        }
        return bizCodes;
    }

    /**
     * 退役自清理：同业务段内本次未出现的 {@code source=rpc} 行（提供方已下线能力）物理删除，
     * open_app_api 授权引用同事务移除（事务在 runIgnore 扇出线程内开启，绑定共享主库）。
     * 种子/内置行（source=seed）与段外行不触碰；逐段独立提交，单段失败不阻断其余（下轮自愈）。
     */
    private int pruneStale(Map<String, Set<String>> bizCodes, Long world) {
        int pruned = 0;
        for (Map.Entry<String, Set<String>> entry : bizCodes.entrySet()) {
            if (entry.getKey().isEmpty()) {
                continue; // 编码非法组（校验已告警跳过），不据此清理
            }
            List<OpenApiDef> stale = TenantContext.runIgnore(() -> apiMapper.selectList(
                            new LambdaQueryWrapper<OpenApiDef>()
                                    .eq(OpenApiDef::getTenantId, world)
                                    .eq(OpenApiDef::getSource, OpenApiDef.SOURCE_RPC)
                                    .likeRight(OpenApiDef::getCode, "open:" + entry.getKey() + ":")))
                    .stream().filter(row -> !entry.getValue().contains(row.getCode())).toList();
            if (stale.isEmpty()) {
                continue;
            }
            transactionTemplate.executeWithoutResult(tx -> {
                for (OpenApiDef row : stale) {
                    TenantContext.runIgnore(() -> appApiMapper.deletePhysicallyByApi(row.getId(), world));
                    TenantContext.runIgnore(() -> apiMapper.deletePhysically(row.getId()));
                }
            });
            pruned += stale.size();
            log.info("[open-api] 目录退役清理: tenant={} 段=open:{}: 移除={}", world, entry.getKey(),
                    stale.stream().map(OpenApiDef::getCode).toList());
        }
        return pruned;
    }

    private int upsertAll(List<OpenApiDescriptor> descriptors, Long world) {
        int applied = 0;
        for (OpenApiDescriptor descriptor : descriptors) {
            if (upsert(descriptor, world)) {
                applied++;
            }
        }
        return applied;
    }

    /** 按条目业务段（open:&lt;biz&gt;:&lt;resource&gt;）分组，保持出现顺序；编码非法的归入空 key 组（交由校验告警） */
    private static Map<String, List<OpenApiDescriptor>> groupByBiz(List<OpenApiDescriptor> descriptors) {
        Map<String, List<OpenApiDescriptor>> groups = new LinkedHashMap<>();
        for (OpenApiDescriptor descriptor : descriptors) {
            if (descriptor == null) {
                continue;
            }
            String code = descriptor.getCode();
            String[] parts = code == null ? new String[0] : code.split(":");
            groups.computeIfAbsent(parts.length == 3 ? parts[1] : "", k -> new ArrayList<>()).add(descriptor);
        }
        return groups;
    }

    /** 窗口执行权：同业务一个窗口只放行一批；无锁/Redis 异常降级直收（返回 true） */
    private boolean acquireWindow(LockOps ops, String biz) {
        if (ops == null || biz.isEmpty()) {
            return true;
        }
        try {
            if (ops.tryEnter("mse:open:api-recv:" + biz, RECV_WINDOW)) {
                return true;
            }
        } catch (Exception e) {
            log.debug("[open-api] 推送收敛标记不可用，降级直收（biz={}）: {}", biz, e.getMessage());
            return true;
        }
        log.debug("[open-api] 目录窗口内已处理（biz={}），跳过本次推送", biz);
        return false;
    }

    private String validate(OpenApiDescriptor descriptor) {
        String code = descriptor.getCode();
        if (code == null || code.isBlank() || !code.matches("open:[\\w-]+:[\\w-]+")) {
            return "code 须为 open:<biz>:<resource> 格式";
        }
        if (isBlank(descriptor.getName())) {
            return "name 不能为空";
        }
        String path = descriptor.getPath();
        if (path == null || !path.startsWith("/open/api/") || path.length() > 200) {
            return "path 须以 /open/api/ 开头（业务前缀段，如 /open/api/oa/...）";
        }
        String method = descriptor.getHttpMethod();
        if (method == null || !ALLOWED_METHODS.contains(method.trim().toUpperCase(Locale.ROOT))) {
            return "httpMethod 须为 GET/POST/PUT/DELETE";
        }
        String target = descriptor.getTarget();
        if (target == null || !target.matches("[\\w.]+#\\w+")) {
            return "target 须为 契约接口全名#方法名";
        }
        String interfaceName = target.substring(0, target.indexOf('#'));
        if (properties.getRpcAllowedPackages().stream().noneMatch(interfaceName::startsWith)) {
            return "target 接口不在分发白名单内: " + interfaceName;
        }
        return null;
    }

    /**
     * 世界内幂等 upsert（runIgnore + 显式 tenant 条件；描述变更才触碰行，启停/授权不覆盖）。
     *
     * <p>租户世界（world != 0）行 id 对齐种子脚本副本约定：查得同 code 平台行时，新增即按
     * {@link #tenantCopyId} 显式 id 插入（ASSIGN_ID 不覆盖显式值）；存量行 id 不一致（早于本
     * 约定推送的雪花 id 行）先经 {@link #alignTenantCopyId} 改写收敛。平台行（世界 0）是副本
     * 公式的源，保持雪花 id 不动。查无平台行（理论不至：扇出清单 0 位先种平台）维持原行为。
     */
    private boolean upsert(OpenApiDescriptor descriptor, Long world) {
        OpenApiDef existing = TenantContext.runIgnore(() -> apiMapper.selectOne(new LambdaQueryWrapper<OpenApiDef>()
                .eq(OpenApiDef::getTenantId, world)
                .eq(OpenApiDef::getCode, descriptor.getCode())
                .last("LIMIT 1")));
        Long copyId = null;
        if (world != 0L) {
            OpenApiDef platformRow = TenantContext.runIgnore(() -> apiMapper.selectOne(new LambdaQueryWrapper<OpenApiDef>()
                    .eq(OpenApiDef::getTenantId, 0L)
                    .eq(OpenApiDef::getCode, descriptor.getCode())
                    .last("LIMIT 1")));
            if (platformRow != null) {
                copyId = tenantCopyId(platformRow.getId(), world);
            }
        }
        if (existing == null) {
            OpenApiDef api = new OpenApiDef();
            if (copyId != null) {
                api.setId(copyId);
            }
            api.setTenantId(world);
            api.setCode(descriptor.getCode());
            api.setName(descriptor.getName());
            api.setPath(descriptor.getPath());
            api.setHttpMethod(descriptor.getHttpMethod().trim().toUpperCase(Locale.ROOT));
            api.setTarget(descriptor.getTarget());
            api.setSource(OpenApiDef.SOURCE_RPC);
            api.setStatus(OpenApiDef.STATUS_ENABLED);
            api.setRemark(truncate(descriptor.getDescription(), 255));
            apiMapper.insert(api);
            log.info("[open-api] 目录新增: tenant={} {} -> {}", world, descriptor.getCode(), descriptor.getPath());
            return true;
        }
        if (copyId != null && !copyId.equals(existing.getId())) {
            alignTenantCopyId(existing.getId(), copyId, world, descriptor.getCode());
        }
        boolean changed = !eq(existing.getName(), descriptor.getName())
                || !eq(existing.getPath(), descriptor.getPath())
                || !eq(existing.getHttpMethod(), descriptor.getHttpMethod().trim().toUpperCase(Locale.ROOT))
                || !eq(existing.getTarget(), descriptor.getTarget())
                || !eq(existing.getRemark(), truncate(descriptor.getDescription(), 255));
        if (!changed) {
            return false; // 定义未变：不触碰 status/授权，幂等
        }
        OpenApiDef patch = new OpenApiDef();
        patch.setId(copyId != null ? copyId : existing.getId());
        patch.setName(descriptor.getName());
        patch.setPath(descriptor.getPath());
        patch.setHttpMethod(descriptor.getHttpMethod().trim().toUpperCase(Locale.ROOT));
        patch.setTarget(descriptor.getTarget());
        patch.setSource(OpenApiDef.SOURCE_RPC);
        patch.setRemark(truncate(descriptor.getDescription(), 255));
        apiMapper.updateById(patch);
        log.info("[open-api] 目录更新: tenant={} {} -> {}", world, descriptor.getCode(), descriptor.getPath());
        return true;
    }

    /**
     * 租户副本确定性 id——与每租户种子脚本 V20261010_03_open__tenant_seed.sql 的副本公式
     * 严格一致（副本 id = 平台行 id + tenant_id % 100000000 * 1000，租户编号取低 8 位参与
     * 运算防 BIGINT 溢出）。运行期播种与种子脚本两路谁先到都产出同一行 id：否则按雪花 id
     * 插入的租户行会令脚本按副本 id 的 NOT EXISTS 判重漏判，INSERT 撞 uk_tenant_code 唯一键
     * （每租户通道失败即中止，账本永不推进、懒初始化无限重试）。
     */
    static Long tenantCopyId(Long platformRowId, long tenantId) {
        return platformRowId + (tenantId % 100_000_000L) * 1_000L;
    }

    /**
     * 存量雪花 id 租户行改写为确定性副本 id：同事务改写 open_api.id 与 open_app_api.api_id
     * 引用（open_api.id 的唯一外部引用列，日志表存 api_code 不受影响），中途失败整体回滚、
     * 下轮播种幂等重试；改写后种子脚本按副本 id 判重命中、INSERT 空操作，账本正常推进。
     * 事务在 runIgnore 上下文内开启（扇出线程），绑定共享主库，方法内不切世界。
     */
    private void alignTenantCopyId(Long legacyId, Long copyId, Long world, String code) {
        transactionTemplate.executeWithoutResult(tx -> {
            TenantContext.runIgnore(() -> apiMapper.update(null, new LambdaUpdateWrapper<OpenApiDef>()
                    .eq(OpenApiDef::getId, legacyId)
                    .eq(OpenApiDef::getTenantId, world)
                    .set(OpenApiDef::getId, copyId)));
            TenantContext.runIgnore(() -> appApiMapper.update(null, new LambdaUpdateWrapper<OpenAppApi>()
                    .eq(OpenAppApi::getApiId, legacyId)
                    .eq(OpenAppApi::getTenantId, world)
                    .set(OpenAppApi::getApiId, copyId)));
        });
        log.info("[open-api] 目录副本 id 对齐: tenant={} {} {} -> {}", world, code, legacyId, copyId);
    }

    /**
     * 目录启停（管理端治理动作）：本世界行级语义——租户会话停/启本租户目录行，
     * 平台会话治理平台世界行（tenant_id=0，平台域凭证的目录）；停用即时生效（本世界）。
     */
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != OpenApiDef.STATUS_ENABLED && status != OpenApiDef.STATUS_DISABLED)) {
            throw new ServiceException(400, "status 仅允许 0 启用 / 1 停用");
        }
        Long world = TenantContext.getRequiredTenantId();
        // runIgnore + 显式 (id, tenant_id) 条件：先验本世界归属，防跨世界改动
        OpenApiDef existing = TenantContext.runIgnore(() -> apiMapper.selectOne(new LambdaQueryWrapper<OpenApiDef>()
                .eq(OpenApiDef::getId, id)
                .eq(OpenApiDef::getTenantId, world)
                .last("LIMIT 1")));
        if (existing == null) {
            throw new ServiceException(404, "API 不存在");
        }
        OpenApiDef patch = new OpenApiDef();
        patch.setId(id);
        patch.setStatus(status);
        TenantContext.runIgnore(() -> apiMapper.updateById(patch));
        catalog.evict();
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
