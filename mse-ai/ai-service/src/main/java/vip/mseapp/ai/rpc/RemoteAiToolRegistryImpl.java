package vip.mseapp.ai.rpc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import vip.mseapp.ai.api.RemoteAiToolRegistry;
import vip.mseapp.ai.api.dto.AiToolDescriptor;
import vip.mseapp.ai.entity.AiTool;
import vip.mseapp.ai.mapper.AiToolMapper;
import vip.mseapp.core.annotation.RpcService;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.redis.config.MseRedisAutoConfiguration.LockOps;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * AI 工具登记契约实现：业务服务（dubbo 模式）经 ai-api 推送器自动登记工具描述。
 *
 * <p>幂等 upsert——按 toolCode 全局查（runIgnore 跨租户）：不存在则新增平台行
 * （tenant_id=0，source=rpc，rpc_group=提供方服务名）；同一提供方重推仅在有变化时同步
 * name/description/schema，管理端启停状态与备注保留；toolCode 已被其他提供方或
 * 租户自有登记占用时跳过并告警，防多提供方互相覆盖。
 *
 * <p>多实例收敛：提供方每个实例都会周期重推（内容相同），接收端以 tryEnter 窗口标记
 * 把同一提供方一个窗口内的重复推送收敛为一次（提供方实例几乎同时就绪，推送成簇到达），
 * 错峰推送只多一次比对查询、变更比对保证零写；Redis 异常降级直收。并发首启撞
 * ai_tool 唯一键 uk_tool_code 时落败方转同步既有行。
 */
@RpcService(RemoteAiToolRegistry.class)
public class RemoteAiToolRegistryImpl implements RemoteAiToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(RemoteAiToolRegistryImpl.class);

    private static final String DEFAULT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    /** 同提供方推送收敛窗口：覆盖多实例成簇推送的时间差，自愈重推由下个窗口兜底 */
    private static final Duration RECV_WINDOW = Duration.ofSeconds(30);

    private final AiToolMapper toolMapper;
    private final ObjectProvider<LockOps> lockOps;

    public RemoteAiToolRegistryImpl(AiToolMapper toolMapper, ObjectProvider<LockOps> lockOps) {
        this.toolMapper = toolMapper;
        this.lockOps = lockOps;
    }

    @Override
    public void register(String providerGroup, List<AiToolDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return;
        }
        String group = providerGroup == null || providerGroup.isBlank() ? "unknown" : providerGroup;
        LockOps ops = lockOps.getIfAvailable();
        if (ops != null) {
            try {
                if (!ops.tryEnter("mse:ai:tool-recv:" + group, RECV_WINDOW)) {
                    log.debug("[mse-ai] 工具自登记窗口内已处理（provider={}），跳过本次推送", group);
                    return;
                }
            } catch (Exception e) {
                log.debug("[mse-ai] 推送收敛标记不可用，降级直收（provider={}）: {}", group, e.getMessage());
            }
        }
        int changed = 0;
        for (AiToolDescriptor descriptor : descriptors) {
            try {
                if (upsert(descriptor, group)) {
                    changed++;
                }
            } catch (Exception e) {
                log.warn("[mse-ai] 工具自登记失败，跳过: {} - {}",
                        descriptor == null ? null : descriptor.getToolCode(), e.getMessage());
            }
        }
        if (changed > 0) {
            log.info("[mse-ai] 工具自登记完成: provider={}, 推送 {} 条（新增/更新 {}）", group, descriptors.size(), changed);
        }
    }

    private boolean upsert(AiToolDescriptor descriptor, String group) {
        String toolCode = descriptor == null ? null : descriptor.getToolCode();
        if (toolCode == null || toolCode.isBlank()) {
            log.warn("[mse-ai] 工具自登记缺少 toolCode，跳过（provider={}）", group);
            return false;
        }
        AiTool existing = findByCode(toolCode);
        String schema = descriptor.getParametersJsonSchema() == null
                ? DEFAULT_SCHEMA : descriptor.getParametersJsonSchema();
        if (existing == null) {
            try {
                // ai_tool 平台公共行：runIgnore 不路由、落 primary=平台库（0.1.0 起，
                // master + base/platform 双通道建表）；insert 缺省填充 tenant_id=0
                AiTool tool = TenantContext.runIgnore(() -> {
                    AiTool row = new AiTool();
                    row.setToolCode(toolCode);
                    row.setName(descriptor.getName());
                    row.setDescription(descriptor.getDescription());
                    row.setParametersJsonSchema(schema);
                    row.setSource("rpc");
                    row.setRpcGroup(group);
                    row.setStatus(0);
                    row.setRemark("自动登记(推送)");
                    toolMapper.insert(row);
                    return row;
                });
                log.info("[mse-ai] 工具自登记新增: {}（provider={}）", tool.getToolCode(), group);
                return true;
            } catch (DataIntegrityViolationException e) {
                // 并发首启撞唯一键 uk_tool_code：落败方转同步既有行
                existing = findByCode(toolCode);
                if (existing == null) {
                    throw e;
                }
                log.debug("[mse-ai] 工具自登记并发落败，转同步既有行: {}", toolCode);
            }
        }
        Long existingTenant = existing.getTenantId();
        if (existingTenant != null && existingTenant != 0L) {
            log.warn("[mse-ai] 工具自登记跳过: {} 为租户自有登记（tenant_id={}），不自动覆盖",
                    toolCode, existingTenant);
            return false;
        }
        if (!group.equals(existing.getRpcGroup())) {
            log.warn("[mse-ai] 工具自登记跳过: {} 已由 {} 登记（本次推送 {}），防覆盖",
                    toolCode, existing.getRpcGroup(), group);
            return false;
        }
        boolean dirty = !Objects.equals(existing.getName(), descriptor.getName())
                || !Objects.equals(existing.getDescription(), descriptor.getDescription())
                || !Objects.equals(existing.getParametersJsonSchema(), schema);
        if (!dirty) {
            return false;
        }
        AiTool target = existing;
        TenantContext.runIgnore(() -> {
            target.setName(descriptor.getName());
            target.setDescription(descriptor.getDescription());
            target.setParametersJsonSchema(schema);
            toolMapper.updateById(target);
            return null;
        });
        return true;
    }

    /** 平台世界（tenant 0）按码查登记行：工具已真租户化（各租户有同码副本），推送登记只治理平台行 */
    private AiTool findByCode(String toolCode) {
        return TenantContext.runIgnore(() -> toolMapper.selectOne(new LambdaQueryWrapper<AiTool>()
                .eq(AiTool::getToolCode, toolCode)
                .eq(AiTool::getTenantId, MseConstants.PLATFORM_TENANT_ID)
                .last("LIMIT 1")));
    }
}
