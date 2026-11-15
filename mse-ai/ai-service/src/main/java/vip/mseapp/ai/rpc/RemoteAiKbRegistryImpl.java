package vip.mseapp.ai.rpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import vip.mseapp.ai.api.RemoteAiKbRegistry;
import vip.mseapp.ai.api.dto.AiKbDescriptor;
import vip.mseapp.core.annotation.RpcService;
import vip.mseapp.redis.config.MseRedisAutoConfiguration.LockOps;

import java.time.Duration;
import java.util.List;

/**
 * 知识库声明登记契约实现：业务服务（dubbo 模式）经 ai-api 推送器自动登记知识库声明。
 *
 * <p>幂等 upsert 语义与 {@link LocalKbDeclRegistrar} 完全一致（共用其 upsert 引擎，
 * source=rpc + rpc_group=提供方服务名）：不存在则新增平台行；同一提供方重推仅在有变化时
 * 同步 name/description；code 已被其他提供方 rpc 登记占用时跳过防互相覆盖。
 *
 * <p>多实例收敛：提供方每个实例都会周期重推（内容相同），接收端以 tryEnter 窗口标记
 * 把同一提供方一个窗口内的重复推送收敛为一次；Redis 异常降级直收。
 */
@RpcService(RemoteAiKbRegistry.class)
public class RemoteAiKbRegistryImpl implements RemoteAiKbRegistry {

    private static final Logger log = LoggerFactory.getLogger(RemoteAiKbRegistryImpl.class);

    /** 同提供方推送收敛窗口：覆盖多实例成簇推送的时间差，自愈重推由下个窗口兜底 */
    private static final Duration RECV_WINDOW = Duration.ofSeconds(30);

    private final LocalKbDeclRegistrar registrar;
    private final ObjectProvider<LockOps> lockOps;

    public RemoteAiKbRegistryImpl(LocalKbDeclRegistrar registrar, ObjectProvider<LockOps> lockOps) {
        this.registrar = registrar;
        this.lockOps = lockOps;
    }

    @Override
    public void register(String providerGroup, List<AiKbDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return;
        }
        String group = providerGroup == null || providerGroup.isBlank() ? "unknown" : providerGroup;
        LockOps ops = lockOps.getIfAvailable();
        if (ops != null) {
            try {
                if (!ops.tryEnter("mse:ai:kb-decl-recv:" + group, RECV_WINDOW)) {
                    log.debug("[mse-ai] 知识库声明登记窗口内已处理（provider={}），跳过本次推送", group);
                    return;
                }
            } catch (Exception e) {
                log.debug("[mse-ai] 推送收敛标记不可用，降级直收（provider={}）: {}", group, e.getMessage());
            }
        }
        int changed = 0;
        for (AiKbDescriptor descriptor : descriptors) {
            try {
                if (registrar.upsert(descriptor, "rpc", group)) {
                    changed++;
                }
            } catch (Exception e) {
                log.warn("[mse-ai] 知识库声明登记失败，跳过: {} - {}",
                        descriptor == null ? null : descriptor.getCode(), e.getMessage());
            }
        }
        if (changed > 0) {
            log.info("[mse-ai] 知识库声明登记完成: provider={}, 推送 {} 条（新增/更新 {}）",
                    group, descriptors.size(), changed);
        }
    }
}
