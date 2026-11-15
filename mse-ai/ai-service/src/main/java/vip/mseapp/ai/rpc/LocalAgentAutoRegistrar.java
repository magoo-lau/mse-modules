package vip.mseapp.ai.rpc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import vip.mseapp.ai.api.AiAgentProvider;
import vip.mseapp.ai.api.dto.AiAgentDescriptor;
import vip.mseapp.ai.entity.AiAgent;
import vip.mseapp.ai.mapper.AiAgentMapper;
import vip.mseapp.ai.service.AiAgentService;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.redis.config.MseRedisAutoConfiguration.LockOps;

import java.util.List;
import java.util.Objects;

/**
 * 业务智能体自动登记：把容器内全部 {@link AiAgentProvider} 声明（单体聚合形态下各业务积木的
 * 声明 Bean）启动即幂等 upsert 进 ai_agent 平台行，各业务（MES/OA 等）的场景智能体因此开箱
 * 即在智能体管理可见可映射，替代「每业务在种子 SQL 或管理页手工维护」；微服务形态由 ai-api
 * 推送器登记（source=rpc），与本类互补、行为一致。
 *
 * <p>upsert 规则：不存在 → 新增平台行（tenant_id=0，scope=platform，status=0 已发布，
 * source=local，simple 编排）；已存在的平台行（含历史种子 builtin 行）按提供方权威采纳刷新
 * name/description/systemPrompt/toolCodes/kbCodes/chartDirect 并归一 source（模型/温度/
 * 开场白/启停/备注管理侧保留）——能进本扫描即提供方声明在本容器内，声明即权威，
 * 租户自有行（tenant_id≠0）不碰。
 *
 * <p>多实例防重：整个扫描经 LockOps 单飞（忙则跳过不等待），同一时刻只有一个实例执行登记；
 * 无锁/Redis 异常降级直执，并发首启撞 uk_agent_tenant 唯一键时落败方转同步既有行。
 * 错峰启动的后启实例仍做一次只读扫描，变更比对保证零写。
 *
 * <p>rpc 包不经组件扫描（与各契约实现一致），由 {@code AiServiceAutoConfiguration} 以
 * {@code @Bean} 显式注册——勿改回 {@code @Component}（该包不在装配扫描清单内，注解不生效）。
 */
public class LocalAgentAutoRegistrar {

    private static final Logger log = LoggerFactory.getLogger(LocalAgentAutoRegistrar.class);

    /** 启动登记单飞锁：多实例同时启动只有一个实例执行扫描，其余实例不碰数据库 */
    private static final String LOCK_KEY = "mse:ai:agent-register";

    private final ObjectProvider<AiAgentProvider> providers;
    private final AiAgentMapper agentMapper;
    private final ObjectProvider<LockOps> lockOps;

    public LocalAgentAutoRegistrar(ObjectProvider<AiAgentProvider> providers, AiAgentMapper agentMapper,
                                   ObjectProvider<LockOps> lockOps) {
        this.providers = providers;
        this.agentMapper = agentMapper;
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
            log.warn("[mse-ai] 分布式锁不可用，降级直执智能体声明登记（唯一键兜底防重）: {}", e.getMessage());
            registerAll();
            return;
        }
        if (!executed) {
            log.info("[mse-ai] 其他实例正在执行智能体登记，本实例跳过（锁: {}）", LOCK_KEY);
        }
    }

    private void registerAll() {
        int scanned = 0;
        int changed = 0;
        for (AiAgentProvider provider : providers) {
            List<AiAgentDescriptor> declared;
            try {
                declared = provider.agents();
            } catch (Exception e) {
                log.warn("[mse-ai] 收集智能体声明失败，跳过（bean={}）: {}",
                        provider.getClass().getName(), e.getMessage());
                continue;
            }
            if (declared == null) {
                continue;
            }
            for (AiAgentDescriptor descriptor : declared) {
                scanned++;
                try {
                    if (upsert(descriptor, AiAgentService.SOURCE_LOCAL, null)) {
                        changed++;
                    }
                } catch (Exception e) {
                    log.warn("[mse-ai] 智能体声明登记失败，跳过: {} - {}",
                            safeCode(descriptor), e.getMessage());
                }
            }
        }
        if (changed > 0) {
            log.info("[mse-ai] 智能体声明登记完成: 扫描 {} 条声明（新增/更新 {}）", scanned, changed);
        } else {
            log.debug("[mse-ai] 智能体声明登记: 扫描 {} 条声明，无变更", scanned);
        }
    }

    /**
     * 幂等 upsert 单条声明（本地登记与推送登记共用语义，仅 source/rpcGroup 不同）：
     * 平台行不存在则插入；已存在按提供方权威刷新内容四字段并归一 source/rpcGroup。
     */
    boolean upsert(AiAgentDescriptor descriptor, String source, String rpcGroup) {
        String agentCode = descriptor == null ? null : descriptor.getAgentCode();
        if (agentCode == null || agentCode.isBlank()) {
            log.warn("[mse-ai] 智能体声明登记缺少 agentCode，跳过");
            return false;
        }
        AiAgent existing = findByCode(agentCode);
        int chartDirect = Boolean.TRUE.equals(descriptor.getChartDirect()) ? 1 : 0;
        if (existing == null) {
            try {
                // ai_agent 双通道建表（master 通道租户域库 + base/platform 域平台库 V20261002_01）：
                // runIgnore 不路由、落 primary=平台库（0.1.0 起）——登记行即「平台登记源（平台库 0 行）」，
                // 兼作每租户懒补齐的来源；平台行显式 scope=platform + tenant_id=0
                TenantContext.runIgnore(() -> {
                    AiAgent row = new AiAgent();
                    row.setAgentCode(agentCode);
                    row.setName(descriptor.getName());
                    row.setAvatar(descriptor.getAvatar());
                    row.setDescription(descriptor.getDescription());
                    row.setSystemPrompt(descriptor.getSystemPrompt());
                    row.setToolCodes(descriptor.getToolCodes());
                    row.setKbCodes(descriptor.getKbCodes());
                    row.setChartDirect(chartDirect);
                    row.setOrchestrationType("simple");
                    row.setScope(AiAgentService.SCOPE_PLATFORM);
                    row.setTenantId(MseConstants.PLATFORM_TENANT_ID);
                    row.setStatus(0);
                    row.setSource(source);
                    row.setRpcGroup(rpcGroup);
                    row.setRemark("声明登记");
                    agentMapper.insert(row);
                    return null;
                });
                log.info("[mse-ai] 智能体声明登记新增: {}（source={}）", agentCode, source);
                return true;
            } catch (DataIntegrityViolationException e) {
                // 并发首启撞唯一键 uk_agent_tenant：落败方转同步既有行
                existing = findByCode(agentCode);
                if (existing == null) {
                    throw e;
                }
                log.debug("[mse-ai] 智能体声明登记并发落败，转同步既有行: {}", agentCode);
            }
        }
        if (existing.getTenantId() != null && existing.getTenantId() != 0L) {
            log.warn("[mse-ai] 智能体声明登记跳过: {} 为租户自有行（tenant_id={}），不自动覆盖",
                    agentCode, existing.getTenantId());
            return false;
        }
        // 推送登记防多提供方互相覆盖：同码已被其他提供方 rpc 登记时本次跳过（本地登记无此约束
        // ——能进本地扫描即 Bean 在本容器，声明即权威，历史 rpc/local 行一并采纳归一）
        if (AiAgentService.SOURCE_RPC.equals(source) && rpcGroup != null
                && AiAgentService.SOURCE_RPC.equals(existing.getSource())
                && !Objects.equals(existing.getRpcGroup(), rpcGroup)) {
            log.warn("[mse-ai] 智能体声明登记跳过: {} 已由提供方 {} 登记（本次 {}），防覆盖",
                    agentCode, existing.getRpcGroup(), rpcGroup);
            return false;
        }
        // 内容六字段以声明为权威（kbCodes=声明绑定知识库编码，副本懒补齐时按编码建壳回填 kb_ids；
        // chartDirect=直出图表声明默认值）；模型/温度/开场白/启停/备注管理侧保留，source/rpcGroup 归一登记值
        boolean dirty = !Objects.equals(existing.getName(), descriptor.getName())
                || !Objects.equals(existing.getDescription(), descriptor.getDescription())
                || !Objects.equals(existing.getSystemPrompt(), descriptor.getSystemPrompt())
                || !Objects.equals(existing.getToolCodes(), descriptor.getToolCodes())
                || !Objects.equals(existing.getKbCodes(), descriptor.getKbCodes())
                || !Objects.equals(existing.getChartDirect(), chartDirect)
                || !Objects.equals(existing.getSource(), source)
                || !Objects.equals(existing.getRpcGroup(), rpcGroup);
        if (!dirty) {
            return false;
        }
        AiAgent target = existing;
        TenantContext.runIgnore(() -> {
            target.setName(descriptor.getName());
            target.setDescription(descriptor.getDescription());
            target.setSystemPrompt(descriptor.getSystemPrompt());
            target.setToolCodes(descriptor.getToolCodes());
            target.setKbCodes(descriptor.getKbCodes());
            target.setChartDirect(chartDirect);
            target.setSource(source);
            target.setRpcGroup(rpcGroup);
            agentMapper.updateById(target);
            return null;
        });
        return true;
    }

    /** 平台世界（tenant 0）按码查登记行：智能体已真租户化（各租户有同码副本），声明登记只治理平台行 */
    private AiAgent findByCode(String agentCode) {
        return TenantContext.runIgnore(() -> agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
                .eq(AiAgent::getAgentCode, agentCode)
                .eq(AiAgent::getTenantId, MseConstants.PLATFORM_TENANT_ID)
                .last("LIMIT 1")));
    }

    private String safeCode(AiAgentDescriptor descriptor) {
        return descriptor == null ? null : descriptor.getAgentCode();
    }
}
