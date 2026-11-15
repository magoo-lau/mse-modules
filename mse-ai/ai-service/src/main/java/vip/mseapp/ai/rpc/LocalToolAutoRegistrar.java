package vip.mseapp.ai.rpc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import vip.mseapp.ai.api.RemoteAiTool;
import vip.mseapp.ai.api.dto.AiToolDescriptor;
import vip.mseapp.ai.entity.AiTool;
import vip.mseapp.ai.mapper.AiToolMapper;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.redis.config.MseRedisAutoConfiguration.LockOps;

import java.util.Objects;

/**
 * 系统工具自动登记：把容器内全部 {@link RemoteAiTool} Bean（内置工具 + 单体聚合形态下
 * 各业务积木的实现）启动即幂等 upsert 进 ai_tool，工具中心因此可统一启停/测试/审计，
 * 替代历史「从提供方拉取」手动兜底；单体与微服务两种形态行为一致。
 *
 * <p>upsert 规则：不存在 → 新增平台行（tenant_id=0，status=0，source=builtin/local，
 * 内置按类包名 vip.mseapp.ai.* 判定）；已存在且 source=builtin/local/rpc → 有变化才同步
 * name/description/schema（管理端启停与备注保留）——rpc 行一并同步是因为能进本扫描即提供方
 * Bean 已在宿主容器内（单体聚合形态），本地 Bean 就是描述的权威来源，否则种子/历史推送的
 * rpc 行永远拿不到补充后的参数描述；http/dify 或租户自有登记跳过防覆盖。
 * dubbo 模式下业务服务的远程工具仍由 ai-api 推送器登记（source=rpc），与本类互补。
 *
 * <p>多实例防重：整个扫描经 LockOps 单飞（忙则跳过不等待），同一时刻只有一个实例执行登记；
 * 无锁/Redis 异常降级直执，并发首启撞 ai_tool 唯一键 uk_tool_code 时落败方转同步既有行。
 * 错峰启动的后启实例仍做一次只读扫描，变更比对保证零写。
 *
 * <p>rpc 包不经组件扫描（与各契约实现一致），由 {@code AiServiceAutoConfiguration} 以
 * {@code @Bean} 显式注册——勿改回 {@code @Component}（该包不在装配扫描清单内，注解不生效）。
 */
public class LocalToolAutoRegistrar {

    private static final Logger log = LoggerFactory.getLogger(LocalToolAutoRegistrar.class);

    private static final String DEFAULT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    /** 启动登记单飞锁：多实例同时启动只有一个实例执行扫描，其余实例不碰数据库 */
    private static final String LOCK_KEY = "mse:ai:tool-register";

    private final ObjectProvider<RemoteAiTool> localTools;
    private final AiToolMapper toolMapper;
    private final ObjectProvider<LockOps> lockOps;

    public LocalToolAutoRegistrar(ObjectProvider<RemoteAiTool> localTools, AiToolMapper toolMapper,
                                  ObjectProvider<LockOps> lockOps) {
        this.localTools = localTools;
        this.toolMapper = toolMapper;
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
            log.warn("[mse-ai] 分布式锁不可用，降级直执本地工具登记（唯一键兜底防重）: {}", e.getMessage());
            registerAll();
            return;
        }
        if (!executed) {
            log.info("[mse-ai] 其他实例正在执行工具登记，本实例跳过（锁: {}）", LOCK_KEY);
        }
    }

    private void registerAll() {
        int scanned = 0;
        int changed = 0;
        for (RemoteAiTool tool : localTools) {
            scanned++;
            try {
                if (upsert(tool)) {
                    changed++;
                }
            } catch (Exception e) {
                log.warn("[mse-ai] 本地工具自动登记失败，跳过: {} - {}",
                        safeCode(tool), e.getMessage());
            }
        }
        if (changed > 0) {
            log.info("[mse-ai] 本地工具自动登记完成: 扫描 {} 个 Bean（新增/更新 {}）", scanned, changed);
        } else {
            log.debug("[mse-ai] 本地工具自动登记: 扫描 {} 个 Bean，无变更", scanned);
        }
    }

    private boolean upsert(RemoteAiTool tool) {
        AiToolDescriptor descriptor = tool.describe();
        String toolCode = descriptor == null ? null : descriptor.getToolCode();
        if (toolCode == null || toolCode.isBlank()) {
            log.warn("[mse-ai] 本地工具自动登记缺少 toolCode，跳过（bean={})", tool.getClass().getName());
            return false;
        }
        String source = tool.getClass().getName().startsWith("vip.mseapp.ai.") ? "builtin" : "local";
        AiTool existing = findByCode(toolCode);
        String schema = descriptor.getParametersJsonSchema() == null
                ? DEFAULT_SCHEMA : descriptor.getParametersJsonSchema();
        if (existing == null) {
            try {
                // ai_tool 平台公共行（tenant_id=0，master 通道租户域库 + base/platform 域平台库双通道
                // 建表）：runIgnore 不路由、落 primary=平台库（0.1.0 起）——登记行即「平台登记源
                // （平台库 0 行）」；insert 缺省填充 tenant_id=0
                TenantContext.runIgnore(() -> {
                    AiTool row = new AiTool();
                    row.setToolCode(toolCode);
                    row.setName(descriptor.getName());
                    row.setDescription(descriptor.getDescription());
                    row.setParametersJsonSchema(schema);
                    row.setSource(source);
                    row.setStatus(0);
                    row.setRemark("自动登记(本地扫描)");
                    toolMapper.insert(row);
                    return null;
                });
                log.info("[mse-ai] 本地工具自动登记新增: {}（source={}）", toolCode, source);
                return true;
            } catch (DataIntegrityViolationException e) {
                // 并发首启撞唯一键 uk_tool_code：落败方转同步既有行
                existing = findByCode(toolCode);
                if (existing == null) {
                    throw e;
                }
                log.debug("[mse-ai] 本地工具自动登记并发落败，转同步既有行: {}", toolCode);
            }
        }
        if (existing.getTenantId() != null && existing.getTenantId() != 0L) {
            log.warn("[mse-ai] 本地工具自动登记跳过: {} 为租户自有登记（tenant_id={}），不自动覆盖",
                    toolCode, existing.getTenantId());
            return false;
        }
        String existingSource = existing.getSource();
        if ("http".equals(existingSource) || "dify".equals(existingSource)) {
            log.debug("[mse-ai] 本地工具自动登记跳过: {} 已为 {} 登记，防覆盖", toolCode, existingSource);
            return false;
        }
        // source=builtin/local/rpc 均以本地 Bean 描述为准（rpc 行仅限 Bean 在本容器的单体聚合形态）
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

    /** 平台世界（tenant 0）按码查登记行：工具已真租户化（各租户有同码副本），启动登记只治理平台行 */
    private AiTool findByCode(String toolCode) {
        return TenantContext.runIgnore(() -> toolMapper.selectOne(new LambdaQueryWrapper<AiTool>()
                .eq(AiTool::getToolCode, toolCode)
                .eq(AiTool::getTenantId, MseConstants.PLATFORM_TENANT_ID)
                .last("LIMIT 1")));
    }

    private String safeCode(RemoteAiTool tool) {
        try {
            return tool.describe().getToolCode();
        } catch (Exception e) {
            return tool.getClass().getName();
        }
    }
}
