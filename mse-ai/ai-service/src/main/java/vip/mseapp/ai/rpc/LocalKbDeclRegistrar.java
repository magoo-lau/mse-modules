package vip.mseapp.ai.rpc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import vip.mseapp.ai.api.AiKbProvider;
import vip.mseapp.ai.api.dto.AiKbDescriptor;
import vip.mseapp.ai.entity.AiKbDecl;
import vip.mseapp.ai.mapper.AiKbDeclMapper;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.redis.config.MseRedisAutoConfiguration.LockOps;

import java.util.List;
import java.util.Objects;

/**
 * 知识库声明自动登记：把容器内全部 {@link AiKbProvider} 声明（单体聚合形态下各业务积木的
 * 声明 Bean）启动即幂等 upsert 进 ai_kb_decl 平台行；微服务形态由 ai-api 推送器登记
 * （source=rpc），与本类互补、行为一致。
 *
 * <p>upsert 规则：不存在 → 新增平台行（tenant_id=0，source=local）；已存在按提供方权威
 * 采纳刷新 name/description 并归一 source/rpcGroup。目录=全局目录，仅平台行，无租户副本；
 * 各租户世界的库壳由 KbService.ensureCodeKb 在智能体副本懒补齐时按本目录幂等创建。
 *
 * <p>多实例防重：整个扫描经 LockOps 单飞（忙则跳过不等待）；无锁/Redis 异常降级直执，
 * 并发首启撞 uk_kb_decl_code 唯一键时落败方转同步既有行。
 *
 * <p>rpc 包不经组件扫描（与各契约实现一致），由 {@code AiServiceAutoConfiguration} 以
 * {@code @Bean} 显式注册——勿改回 {@code @Component}（该包不在装配扫描清单内，注解不生效）。
 */
public class LocalKbDeclRegistrar {

    private static final Logger log = LoggerFactory.getLogger(LocalKbDeclRegistrar.class);

    /** 启动登记单飞锁：多实例同时启动只有一个实例执行扫描，其余实例不碰数据库 */
    private static final String LOCK_KEY = "mse:ai:kb-decl-register";

    private final ObjectProvider<AiKbProvider> providers;
    private final AiKbDeclMapper declMapper;
    private final ObjectProvider<LockOps> lockOps;

    public LocalKbDeclRegistrar(ObjectProvider<AiKbProvider> providers,
                                AiKbDeclMapper declMapper,
                                ObjectProvider<LockOps> lockOps) {
        this.providers = providers;
        this.declMapper = declMapper;
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
            log.warn("[mse-ai] 分布式锁不可用，降级直执知识库声明登记（唯一键兜底防重）: {}", e.getMessage());
            registerAll();
            return;
        }
        if (!executed) {
            log.info("[mse-ai] 其他实例正在执行知识库声明登记，本实例跳过（锁: {}）", LOCK_KEY);
        }
    }

    private void registerAll() {
        int scanned = 0;
        int changed = 0;
        for (AiKbProvider provider : providers) {
            List<AiKbDescriptor> declared;
            try {
                declared = provider.knowledgeBases();
            } catch (Exception e) {
                log.warn("[mse-ai] 收集知识库声明失败，跳过（bean={}）: {}",
                        provider.getClass().getName(), e.getMessage());
                continue;
            }
            if (declared == null) {
                continue;
            }
            for (AiKbDescriptor descriptor : declared) {
                scanned++;
                try {
                    if (upsert(descriptor, "local", null)) {
                        changed++;
                    }
                } catch (Exception e) {
                    log.warn("[mse-ai] 知识库声明登记失败，跳过: {} - {}",
                            safeCode(descriptor), e.getMessage());
                }
            }
        }
        if (changed > 0) {
            log.info("[mse-ai] 知识库声明登记完成: 扫描 {} 条声明（新增/更新 {}）", scanned, changed);
        } else {
            log.debug("[mse-ai] 知识库声明登记: 扫描 {} 条声明，无变更", scanned);
        }
    }

    /**
     * 幂等 upsert 单条声明（本地登记与推送登记共用语义，仅 source/rpcGroup 不同）：
     * 平台行不存在则插入；已存在按提供方权威刷新 name/description 并归一 source/rpcGroup。
     */
    boolean upsert(AiKbDescriptor descriptor, String source, String rpcGroup) {
        String code = descriptor == null ? null : descriptor.getCode();
        if (code == null || code.isBlank()) {
            log.warn("[mse-ai] 知识库声明登记缺少 code，跳过");
            return false;
        }
        AiKbDecl existing = findByCode(code);
        if (existing == null) {
            try {
                TenantContext.runIgnore(() -> {
                    AiKbDecl row = new AiKbDecl();
                    row.setCode(code);
                    row.setName(descriptor.getName());
                    row.setDescription(descriptor.getDescription());
                    row.setSource(source);
                    row.setRpcGroup(rpcGroup);
                    row.setRemark("声明登记");
                    declMapper.insert(row);
                    return null;
                });
                log.info("[mse-ai] 知识库声明登记新增: {}（source={}）", code, source);
                return true;
            } catch (DataIntegrityViolationException e) {
                // 并发首启撞唯一键 uk_kb_decl_code：落败方转同步既有行
                existing = findByCode(code);
                if (existing == null) {
                    throw e;
                }
                log.debug("[mse-ai] 知识库声明登记并发落败，转同步既有行: {}", code);
            }
        }
        if (existing.getTenantId() != null && existing.getTenantId() != 0L) {
            log.warn("[mse-ai] 知识库声明登记跳过: {} 为非平台行（tenant_id={}）", code, existing.getTenantId());
            return false;
        }
        // 推送登记防多提供方互相覆盖：同码已被其他提供方 rpc 登记时本次跳过
        if ("rpc".equals(source) && rpcGroup != null
                && "rpc".equals(existing.getSource())
                && !Objects.equals(existing.getRpcGroup(), rpcGroup)) {
            log.warn("[mse-ai] 知识库声明登记跳过: {} 已由提供方 {} 登记（本次 {}），防覆盖",
                    code, existing.getRpcGroup(), rpcGroup);
            return false;
        }
        boolean dirty = !Objects.equals(existing.getName(), descriptor.getName())
                || !Objects.equals(existing.getDescription(), descriptor.getDescription())
                || !Objects.equals(existing.getSource(), source)
                || !Objects.equals(existing.getRpcGroup(), rpcGroup);
        if (!dirty) {
            return false;
        }
        AiKbDecl target = existing;
        TenantContext.runIgnore(() -> {
            target.setName(descriptor.getName());
            target.setDescription(descriptor.getDescription());
            target.setSource(source);
            target.setRpcGroup(rpcGroup);
            declMapper.updateById(target);
            return null;
        });
        return true;
    }

    /** 平台世界（tenant 0）按码查声明行（库壳创建的元数据来源） */
    private AiKbDecl findByCode(String code) {
        return TenantContext.runIgnore(() -> declMapper.selectOne(new LambdaQueryWrapper<AiKbDecl>()
                .eq(AiKbDecl::getCode, code)
                .eq(AiKbDecl::getTenantId, MseConstants.PLATFORM_TENANT_ID)
                .last("LIMIT 1")));
    }

    private String safeCode(AiKbDescriptor descriptor) {
        return descriptor == null ? null : descriptor.getCode();
    }
}
