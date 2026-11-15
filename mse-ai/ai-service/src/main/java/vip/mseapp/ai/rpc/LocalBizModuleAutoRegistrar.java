package vip.mseapp.ai.rpc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import vip.mseapp.ai.api.AiBizModuleProvider;
import vip.mseapp.ai.api.dto.AiBizModuleDescriptor;
import vip.mseapp.ai.entity.AiBizModule;
import vip.mseapp.ai.mapper.AiBizModuleMapper;
import vip.mseapp.ai.service.AiBizModuleService;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.redis.config.MseRedisAutoConfiguration.LockOps;

import java.util.List;
import java.util.Objects;

/**
 * 业务模块自动登记：把容器内全部 {@link AiBizModuleProvider} 声明（单体聚合形态下各业务
 * 积木的声明 Bean）启动即幂等 upsert 进 ai_biz_module 平台行，通用业务分析入口（biz_query）
 * 因此开箱可路由；微服务形态由 ai-api 推送器登记（source=rpc），与本类互补、行为一致。
 *
 * <p>upsert 规则：不存在 → 新增平台行（tenant_id=0，status=0 启用，source=local）；已存在的
 * 平台行按提供方权威采纳刷新 name/description/serviceName/keywords/tablePrefixes/toolCode
 * 并归一 source/rpcGroup（启停状态/备注管理侧保留）——能进本扫描即提供方声明在本容器内，
 * 声明即权威。模块目录=全局目录，仅平台行，无租户副本可言。
 *
 * <p>多实例防重：整个扫描经 LockOps 单飞（忙则跳过不等待）；无锁/Redis 异常降级直执，
 * 并发首启撞 uk_biz_module_world_code 唯一键时落败方转同步既有行。
 *
 * <p>rpc 包不经组件扫描（与各契约实现一致），由 {@code AiServiceAutoConfiguration} 以
 * {@code @Bean} 显式注册——勿改回 {@code @Component}（该包不在装配扫描清单内，注解不生效）。
 */
public class LocalBizModuleAutoRegistrar {

    private static final Logger log = LoggerFactory.getLogger(LocalBizModuleAutoRegistrar.class);

    /** 启动登记单飞锁：多实例同时启动只有一个实例执行扫描，其余实例不碰数据库 */
    private static final String LOCK_KEY = "mse:ai:biz-module-register";

    private final ObjectProvider<AiBizModuleProvider> providers;
    private final AiBizModuleMapper moduleMapper;
    private final ObjectProvider<LockOps> lockOps;

    public LocalBizModuleAutoRegistrar(ObjectProvider<AiBizModuleProvider> providers,
                                       AiBizModuleMapper moduleMapper,
                                       ObjectProvider<LockOps> lockOps) {
        this.providers = providers;
        this.moduleMapper = moduleMapper;
        this.lockOps = lockOps;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerOnReady() {
        LockOps ops = lockOps.getIfAvailable();
        if (ops == null) {
            registerAll();
            return;
        }
        boolean executed;
        try {
            executed = ops.tryExecute(LOCK_KEY, this::registerAll);
        } catch (Exception e) {
            log.warn("[mse-ai] 分布式锁不可用，降级直执业务模块声明登记（唯一键兜底防重）: {}", e.getMessage());
            registerAll();
            return;
        }
        if (!executed) {
            log.info("[mse-ai] 其他实例正在执行业务模块登记，本实例跳过（锁: {}）", LOCK_KEY);
        }
    }

    private void registerAll() {
        int scanned = 0;
        int changed = 0;
        for (AiBizModuleProvider provider : providers) {
            List<AiBizModuleDescriptor> declared;
            try {
                declared = provider.modules();
            } catch (Exception e) {
                log.warn("[mse-ai] 收集业务模块声明失败，跳过（bean={}）: {}",
                        provider.getClass().getName(), e.getMessage());
                continue;
            }
            if (declared == null) {
                continue;
            }
            for (AiBizModuleDescriptor descriptor : declared) {
                scanned++;
                try {
                    if (upsert(descriptor, AiBizModuleService.SOURCE_LOCAL, null)) {
                        changed++;
                    }
                } catch (Exception e) {
                    log.warn("[mse-ai] 业务模块声明登记失败，跳过: {} - {}",
                            safeCode(descriptor), e.getMessage());
                }
            }
        }
        if (changed > 0) {
            log.info("[mse-ai] 业务模块声明登记完成: 扫描 {} 条声明（新增/更新 {}）", scanned, changed);
        } else {
            log.debug("[mse-ai] 业务模块声明登记: 扫描 {} 条声明，无变更", scanned);
        }
    }

    /**
     * 幂等 upsert 单条声明（本地登记与推送登记共用语义，仅 source/rpcGroup 不同）：
     * 平台行不存在则插入；已存在按提供方权威刷新声明七字段并归一 source/rpcGroup。
     */
    boolean upsert(AiBizModuleDescriptor descriptor, String source, String rpcGroup) {
        String moduleCode = descriptor == null ? null : descriptor.getModuleCode();
        if (moduleCode == null || moduleCode.isBlank()
                || descriptor.getToolCode() == null || descriptor.getToolCode().isBlank()
                || descriptor.getServiceName() == null || descriptor.getServiceName().isBlank()) {
            log.warn("[mse-ai] 业务模块声明登记缺少 moduleCode/toolCode/serviceName，跳过");
            return false;
        }
        AiBizModule existing = findByCode(moduleCode);
        if (existing == null) {
            try {
                // ai_biz_module 双通道建表（master 租户域 + base/platform 域平台库）：
                // runIgnore 不路由、落 primary=平台库（0.1.0 起）——模块目录=全局目录（平台库 0 行），
                // 读取统一走 runIgnore，无租户副本
                TenantContext.runIgnore(() -> {
                    AiBizModule row = new AiBizModule();
                    row.setModuleCode(moduleCode);
                    row.setName(descriptor.getName());
                    row.setDescription(descriptor.getDescription());
                    row.setServiceName(descriptor.getServiceName());
                    row.setKeywords(descriptor.getKeywords());
                    row.setTablePrefixes(descriptor.getTablePrefixes());
                    row.setToolCode(descriptor.getToolCode());
                    row.setSource(source);
                    row.setRpcGroup(rpcGroup);
                    row.setStatus(0);
                    row.setRemark("声明登记");
                    moduleMapper.insert(row);
                    return null;
                });
                log.info("[mse-ai] 业务模块声明登记新增: {}（source={}）", moduleCode, source);
                return true;
            } catch (DataIntegrityViolationException e) {
                // 并发首启撞唯一键 uk_biz_module_world_code：落败方转同步既有行
                existing = findByCode(moduleCode);
                if (existing == null) {
                    throw e;
                }
                log.debug("[mse-ai] 业务模块声明登记并发落败，转同步既有行: {}", moduleCode);
            }
        }
        if (existing.getTenantId() != null && existing.getTenantId() != 0L) {
            log.warn("[mse-ai] 业务模块声明登记跳过: {} 为租户自有行（tenant_id={}），不自动覆盖",
                    moduleCode, existing.getTenantId());
            return false;
        }
        // 推送登记防多提供方互相覆盖：同码已被其他提供方 rpc 登记时本次跳过（本地登记无此约束
        // ——能进本地扫描即 Bean 在本容器，声明即权威，历史 rpc/local 行一并采纳归一）
        if (AiBizModuleService.SOURCE_RPC.equals(source) && rpcGroup != null
                && AiBizModuleService.SOURCE_RPC.equals(existing.getSource())
                && !Objects.equals(existing.getRpcGroup(), rpcGroup)) {
            log.warn("[mse-ai] 业务模块声明登记跳过: {} 已由提供方 {} 登记（本次 {}），防覆盖",
                    moduleCode, existing.getRpcGroup(), rpcGroup);
            return false;
        }
        boolean dirty = !Objects.equals(existing.getName(), descriptor.getName())
                || !Objects.equals(existing.getDescription(), descriptor.getDescription())
                || !Objects.equals(existing.getServiceName(), descriptor.getServiceName())
                || !Objects.equals(existing.getKeywords(), descriptor.getKeywords())
                || !Objects.equals(existing.getTablePrefixes(), descriptor.getTablePrefixes())
                || !Objects.equals(existing.getToolCode(), descriptor.getToolCode())
                || !Objects.equals(existing.getSource(), source)
                || !Objects.equals(existing.getRpcGroup(), rpcGroup);
        if (!dirty) {
            return false;
        }
        AiBizModule target = existing;
        TenantContext.runIgnore(() -> {
            target.setName(descriptor.getName());
            target.setDescription(descriptor.getDescription());
            target.setServiceName(descriptor.getServiceName());
            target.setKeywords(descriptor.getKeywords());
            target.setTablePrefixes(descriptor.getTablePrefixes());
            target.setToolCode(descriptor.getToolCode());
            target.setSource(source);
            target.setRpcGroup(rpcGroup);
            moduleMapper.updateById(target);
            return null;
        });
        return true;
    }

    /** 平台行按码查登记行（模块目录=全局目录，仅平台行） */
    private AiBizModule findByCode(String moduleCode) {
        return TenantContext.runIgnore(() -> moduleMapper.selectOne(new LambdaQueryWrapper<AiBizModule>()
                .eq(AiBizModule::getModuleCode, moduleCode)
                .eq(AiBizModule::getTenantId, MseConstants.PLATFORM_TENANT_ID)
                .last("LIMIT 1")));
    }

    private String safeCode(AiBizModuleDescriptor descriptor) {
        return descriptor == null ? null : descriptor.getModuleCode();
    }
}
