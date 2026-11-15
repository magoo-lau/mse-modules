package vip.mseapp.ai.rpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import vip.mseapp.ai.api.RemoteAiAgentRegistry;
import vip.mseapp.ai.api.dto.AiAgentDescriptor;
import vip.mseapp.ai.service.AiAgentService;
import vip.mseapp.core.annotation.RpcService;
import vip.mseapp.redis.config.MseRedisAutoConfiguration.LockOps;

import java.time.Duration;
import java.util.List;

/**
 * AI 智能体登记契约实现：业务服务（dubbo 模式）经 ai-api 推送器自动登记智能体声明。
 *
 * <p>幂等 upsert 语义与 {@link LocalAgentAutoRegistrar} 完全一致（共用其 upsert 引擎，
 * source=rpc + rpc_group=提供方服务名）：不存在则新增平台行（默认已发布）；同一提供方
 * 重推仅在有变化时同步 name/description/systemPrompt/toolCodes，模型/温度/开场白/启停/
 * 备注保留；agentCode 已被其他提供方 rpc 登记占用时跳过防互相覆盖；租户自有行不碰。
 * 各租户副本由 AiAgentService 可见性/对话入口懒补齐，本类只治理平台行。
 *
 * <p>多实例收敛：提供方每个实例都会周期重推（内容相同），接收端以 tryEnter 窗口标记
 * 把同一提供方一个窗口内的重复推送收敛为一次（提供方实例几乎同时就绪，推送成簇到达），
 * 错峰推送只多一次比对查询、变更比对保证零写；Redis 异常降级直收。
 */
@RpcService(RemoteAiAgentRegistry.class)
public class RemoteAiAgentRegistryImpl implements RemoteAiAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(RemoteAiAgentRegistryImpl.class);

    /** 同提供方推送收敛窗口：覆盖多实例成簇推送的时间差，自愈重推由下个窗口兜底 */
    private static final Duration RECV_WINDOW = Duration.ofSeconds(30);

    private final LocalAgentAutoRegistrar registrar;
    private final ObjectProvider<LockOps> lockOps;

    public RemoteAiAgentRegistryImpl(LocalAgentAutoRegistrar registrar, ObjectProvider<LockOps> lockOps) {
        this.registrar = registrar;
        this.lockOps = lockOps;
    }

    @Override
    public void register(String providerGroup, List<AiAgentDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return;
        }
        String group = providerGroup == null || providerGroup.isBlank() ? "unknown" : providerGroup;
        LockOps ops = lockOps.getIfAvailable();
        if (ops != null) {
            try {
                if (!ops.tryEnter("mse:ai:agent-recv:" + group, RECV_WINDOW)) {
                    log.debug("[mse-ai] 智能体声明登记窗口内已处理（provider={}），跳过本次推送", group);
                    return;
                }
            } catch (Exception e) {
                log.debug("[mse-ai] 推送收敛标记不可用，降级直收（provider={}）: {}", group, e.getMessage());
            }
        }
        int changed = 0;
        for (AiAgentDescriptor descriptor : descriptors) {
            try {
                if (registrar.upsert(descriptor, AiAgentService.SOURCE_RPC, group)) {
                    changed++;
                }
            } catch (Exception e) {
                log.warn("[mse-ai] 智能体声明登记失败，跳过: {} - {}",
                        descriptor == null ? null : descriptor.getAgentCode(), e.getMessage());
            }
        }
        if (changed > 0) {
            log.info("[mse-ai] 智能体声明登记完成: provider={}, 推送 {} 条（新增/更新 {}）",
                    group, descriptors.size(), changed);
        }
    }
}
