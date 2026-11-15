package vip.mseapp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vip.mseapp.ai.entity.AiAgent;
import vip.mseapp.ai.kb.KbService;
import vip.mseapp.ai.mapper.AiAgentMapper;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 智能体管理：按会话世界单边读写——租户会话 = 本租户自有行（scope=tenant），平台会话 = 平台世界行
 * （scope=platform，tenant_id=0）。智能体已全面租户自管：种子智能体经每租户播种
 * （V20261001_03_ai__tenant_seed.sql）成为各租户可编辑副本，不再存在"平台公共行 + 租户只读复制"
 * 模式；平台世界行仅为开放 API 平台凭证 / 无租户上下文 RPC 兜底。
 *
 * <p>业务积木声明的智能体（source=local/rpc）落平台行，本类在可见性/对话入口懒补齐租户副本
 * （{@link #ensureDeclaredTenantCopies(long)}），既有租户零 SQL 即见即用。
 *
 * <p>读写统一 {@link TenantContext#runAs} 随世界路由（一切皆租户）：独立库租户落专属库、共享租户
 * 落绑定共享库（tenant_id 行级注入）、平台世界（0）落平台库（未接入 platform 数据源的环境降级
 * 主库 + tenant_id=0 字段隔离）；显式 tenant_id 条件保留作防御。
 */
@Service
public class AiAgentService {

    public static final String SCOPE_PLATFORM = "platform";
    public static final String SCOPE_TENANT = "tenant";

    /** 声明登记来源（LocalAgentAutoRegistrar / RemoteAiAgentRegistryImpl 写入，租户副本懒补齐的筛选依据） */
    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_RPC = "rpc";

    private static final Logger log = LoggerFactory.getLogger(AiAgentService.class);

    private final AiAgentMapper agentMapper;
    private final AiModelService modelService;
    private final KbService kbService;

    public AiAgentService(AiAgentMapper agentMapper, AiModelService modelService, KbService kbService) {
        this.agentMapper = agentMapper;
        this.modelService = modelService;
        this.kbService = kbService;
    }

    /** 当前会话世界编号（无上下文按平台世界 0 处理，供异步/RPC 兜底） */
    public static long worldId() {
        Long tenantId = TenantContext.getTenantId();
        return tenantId == null ? MseConstants.PLATFORM_TENANT_ID : tenantId;
    }

    /** 管理分页：本世界自有行（租户页展示用） */
    public Page<AiAgent> page(long pageNum, long pageSize, String keyword) {
        long world = worldId();
        LambdaQueryWrapper<AiAgent> wrapper = new LambdaQueryWrapper<AiAgent>()
                .eq(AiAgent::getTenantId, world)
                .orderByDesc(AiAgent::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(AiAgent::getName, keyword).or().like(AiAgent::getAgentCode, keyword));
        }
        return TenantContext.runAs(world, () -> agentMapper.selectPage(new Page<>(pageNum, pageSize), wrapper));
    }

    /** 本世界已发布智能体（对话工作台选择 / RPC 下拉 / 开放 API 清单用） */
    public List<AiAgent> listVisible(Long tenantId) {
        long world = tenantId == null ? MseConstants.PLATFORM_TENANT_ID : tenantId;
        ensureDeclaredTenantCopies(world);
        return TenantContext.runAs(world, () -> agentMapper.selectList(new LambdaQueryWrapper<AiAgent>()
                .eq(AiAgent::getTenantId, world)
                .eq(AiAgent::getStatus, 0)
                .orderByDesc(AiAgent::getId)));
    }

    public AiAgent getById(Long id) {
        // 随环境上下文路由（请求线程均有租户）；归属校验由调用方 save/publish/delete/getVisibleById 兜底
        AiAgent agent = agentMapper.selectById(id);
        if (agent == null) {
            throw new ServiceException(404, "智能体不存在");
        }
        return agent;
    }

    /** 本世界可见详情：tenant_id 等值校验兜底他世界行 */
    public AiAgent getVisibleById(Long id, Long tenantId) {
        long world = tenantId == null ? MseConstants.PLATFORM_TENANT_ID : tenantId;
        AiAgent agent = TenantContext.runAs(world, () -> agentMapper.selectById(id));
        if (agent == null || agent.getTenantId() == null
                || agent.getTenantId() != world) {
            throw new ServiceException(404, "智能体不存在");
        }
        return agent;
    }

    /** 按编码取本世界已发布智能体（对话/RPC/开放 API 链路入口；编码世界内唯一） */
    public AiAgent getPublishedVisible(String agentCode, Long tenantId) {
        long world = tenantId == null ? MseConstants.PLATFORM_TENANT_ID : tenantId;
        ensureDeclaredTenantCopies(world);
        AiAgent agent = TenantContext.runAs(world, () -> agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
                .eq(AiAgent::getAgentCode, agentCode)
                .eq(AiAgent::getTenantId, world)
                .last("LIMIT 1")));
        if (agent == null || agent.getStatus() == null || agent.getStatus() != 0) {
            throw new ServiceException(404, "智能体不存在或未发布: " + agentCode);
        }
        return agent;
    }

    /**
     * 声明智能体租户副本懒补齐：业务积木声明式注册的平台行（source=local/rpc）在本世界缺副本时
     * 补建（scope=tenant、默认发布、不绑模型——模型空回退默认模型），使注册智能体对既有
     * 租户零 SQL 生效（区别于种子脚本的 builtin 播种通道）。声明绑定知识库编码（kbCodes）
     * 的智能体在补齐时按编码在本世界幂等建库壳（KbService.ensureCodeKb）并把解析出的库 id
     * 回填副本 kb_ids（声明式 RAG 绑定）；存量副本 kb_ids 从未绑定（NULL）的同样自愈回填，
     * 租户显式清空（空串）不回填。幂等：同码副本已存在跳过、并发撞 uk_agent_tenant 忽略；
     * 整体容错非致命（失败只影响本次可见性，下个入口重试）。
     */
    public void ensureDeclaredTenantCopies(long world) {
        if (world == MseConstants.PLATFORM_TENANT_ID) {
            return; // 平台世界即登记行本体，无需副本
        }
        try {
            List<AiAgent> declared = TenantContext.runIgnore(() -> agentMapper.selectList(
                    new LambdaQueryWrapper<AiAgent>()
                            .eq(AiAgent::getTenantId, MseConstants.PLATFORM_TENANT_ID)
                            .in(AiAgent::getSource, SOURCE_LOCAL, SOURCE_RPC)
                            .eq(AiAgent::getStatus, 0)));
            if (declared.isEmpty()) {
                return;
            }
            List<String> codes = declared.stream().map(AiAgent::getAgentCode).toList();
            Map<String, String> kbIdsByAgent = resolveDeclaredKbIds(world, declared);
            List<AiAgent> existing = TenantContext.runAs(world, () -> agentMapper.selectList(
                    new LambdaQueryWrapper<AiAgent>()
                            .eq(AiAgent::getTenantId, world)
                            .in(AiAgent::getAgentCode, codes)));
            Map<String, AiAgent> have = existing.stream().collect(
                    Collectors.toMap(AiAgent::getAgentCode, a -> a, (a, b) -> a));
            for (AiAgent origin : declared) {
                AiAgent copy = have.get(origin.getAgentCode());
                String kbIds = kbIdsByAgent.get(origin.getAgentCode());
                if (copy == null) {
                    try {
                        AiAgent row = copyForTenant(origin, world, kbIds);
                        TenantContext.runAs(world, () -> {
                            agentMapper.insert(row);
                            return null;
                        });
                        log.info("[mse-ai] 声明智能体租户副本补齐: {}（tenant={}）", origin.getAgentCode(), world);
                    } catch (DataIntegrityViolationException e) {
                        log.debug("[mse-ai] 声明智能体租户副本并发落败，跳过: {}（tenant={}）",
                                origin.getAgentCode(), world);
                    }
                } else if (kbIds != null && copy.getKbIds() == null
                        && origin.getKbCodes() != null && !origin.getKbCodes().isBlank()) {
                    // 存量声明副本自愈回填：kb_ids 从未绑定（NULL）而声明带编码 → 回填编码与解析 id
                    TenantContext.runAs(world, () -> {
                        agentMapper.update(null, new LambdaUpdateWrapper<AiAgent>()
                                .eq(AiAgent::getId, copy.getId())
                                .set(AiAgent::getKbIds, kbIds)
                                .set(AiAgent::getKbCodes, origin.getKbCodes()));
                        return null;
                    });
                    log.info("[mse-ai] 声明智能体知识库绑定回填: {}（tenant={}）", origin.getAgentCode(), world);
                }
            }
        } catch (Exception e) {
            log.warn("[mse-ai] 声明智能体租户副本补齐失败（不影响本次查询）: {}", e.getMessage());
        }
    }

    /** 声明绑定编码 → 本世界 kb_ids CSV（null=不绑定）；逐智能体解析失败不影响其余（绑定下轮重试） */
    private Map<String, String> resolveDeclaredKbIds(long world, List<AiAgent> declared) {
        Map<String, String> result = new HashMap<>();
        for (AiAgent origin : declared) {
            String csv = origin.getKbCodes();
            if (csv == null || csv.isBlank()) {
                continue;
            }
            List<String> codes = Arrays.stream(csv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (codes.isEmpty()) {
                continue;
            }
            try {
                result.put(origin.getAgentCode(), kbService.resolveCodeKbIds(world, codes));
            } catch (Exception e) {
                log.warn("[mse-ai] 声明智能体知识库编码解析失败（副本照常补齐，绑定下轮重试）: {} - {}",
                        origin.getAgentCode(), e.getMessage());
            }
        }
        return result;
    }

    /** 平台登记行 → 租户副本：内容字段照搬（工具编码全局有效），模型留空由租户自绑；
     *  kbCodes 照搬并同步解析为本世界库壳 id 回填 kb_ids（声明式 RAG 绑定，解析失败留空重试） */
    private AiAgent copyForTenant(AiAgent origin, long world, String kbIds) {
        AiAgent copy = new AiAgent();
        copy.setAgentCode(origin.getAgentCode());
        copy.setName(origin.getName());
        copy.setAvatar(origin.getAvatar());
        copy.setDescription(origin.getDescription());
        copy.setSystemPrompt(origin.getSystemPrompt());
        copy.setToolCodes(origin.getToolCodes());
        copy.setKbCodes(origin.getKbCodes());
        copy.setChartDirect(origin.getChartDirect());
        copy.setKbIds(kbIds);
        copy.setOrchestrationType("simple");
        copy.setScope(SCOPE_TENANT);
        copy.setTenantId(world);
        copy.setStatus(0);
        copy.setSource(origin.getSource());
        return copy;
    }

    /**
     * 新增/修改：归属强制为当前世界（scope 随世界推导：租户世界=tenant、平台世界=platform）；
     * agentCode 世界内唯一（uk(tenant_id, agent_code, deleted)）。
     * flow 编排图保存到草稿（draft_graph_json），显式发布才拷贝为运行版 graph_json——编辑不影响线上。
     */
    public Long save(AiAgent form, Long tenantId) {
        long world = tenantId == null ? MseConstants.PLATFORM_TENANT_ID : tenantId;
        AiAgent agent;
        if (form.getId() == null) {
            agent = new AiAgent();
        } else {
            agent = getById(form.getId());
            // 只能改本世界的行
            if (agent.getTenantId() == null || agent.getTenantId() != world) {
                throw new ServiceException(403, "无权修改该智能体");
            }
            // 平台世界的声明登记行（source=local/rpc）：内容与编排模式以库内值为准（提供方
            // 声明权威，随启动/推送自动同步），表单值不采纳防 API 直调覆盖——仅模型/温度/
            // 开场白/头像/启停/备注接受管理侧修改；租户副本不受限、完全可编辑
            if (world == MseConstants.PLATFORM_TENANT_ID
                    && (SOURCE_LOCAL.equals(agent.getSource()) || SOURCE_RPC.equals(agent.getSource()))) {
                form.setAgentCode(agent.getAgentCode());
                form.setName(agent.getName());
                form.setDescription(agent.getDescription());
                form.setSystemPrompt(agent.getSystemPrompt());
                form.setToolCodes(agent.getToolCodes());
                form.setKbCodes(agent.getKbCodes());
                form.setChartDirect(agent.getChartDirect());
                form.setOrchestrationType(agent.getOrchestrationType());
                form.setGraphJson(null);
            }
        }
        agent.setAgentCode(form.getAgentCode());
        agent.setName(form.getName());
        agent.setAvatar(form.getAvatar());
        agent.setDescription(form.getDescription());
        agent.setSystemPrompt(form.getSystemPrompt());
        if (form.getModelId() != null) {
            // 智能体只能绑定对话模型（向量模型用于知识库 RAG）
            modelService.assertChatModel(form.getModelId());
        }
        agent.setModelId(form.getModelId());
        agent.setTemperature(form.getTemperature());
        agent.setWelcomeMessage(form.getWelcomeMessage());
        agent.setToolCodes(form.getToolCodes());
        agent.setKbIds(form.getKbIds());
        agent.setChartDirect(form.getChartDirect());
        String orchestrationType = form.getOrchestrationType() == null || form.getOrchestrationType().isBlank()
                ? "simple" : form.getOrchestrationType();
        agent.setOrchestrationType(orchestrationType);
        // 画布保存即草稿：请求未携带 graphJson 时不触碰既有草稿（管理弹窗只改基础信息）
        if (form.getGraphJson() != null) {
            agent.setDraftGraphJson(form.getGraphJson());
            if ("flow".equals(orchestrationType)) {
                vip.mseapp.ai.workflow.model.FlowGraph graph =
                        vip.mseapp.ai.workflow.model.FlowGraph.parse(form.getGraphJson());
                if (graph != null) {
                    graph.requireValid();
                }
            }
        }
        agent.setStatus(form.getStatus() == null ? 0 : form.getStatus());
        agent.setRemark(form.getRemark());
        if (agent.getId() == null) {
            // agentCode 世界内唯一：本世界查重（副本共享编码，跨世界查重会误伤）
            Long dup = TenantContext.runAs(world, () -> agentMapper.selectCount(new LambdaQueryWrapper<AiAgent>()
                    .eq(AiAgent::getTenantId, world)
                    .eq(AiAgent::getAgentCode, agent.getAgentCode())));
            if (dup != null && dup > 0) {
                throw new ServiceException(400, "agentCode 已存在: " + agent.getAgentCode());
            }
            agent.setScope(world == MseConstants.PLATFORM_TENANT_ID ? SCOPE_PLATFORM : SCOPE_TENANT);
            agent.setTenantId(world);
            agent.setSource("custom");
            TenantContext.runAs(world, () -> agentMapper.insert(agent));
        } else {
            TenantContext.runAs(world, () -> agentMapper.updateById(agent));
        }
        return agent.getId();
    }

    /**
     * 发布：草稿编排图校验后拷贝为运行版（graph_json）并置已发布。
     * 无草稿但已有运行版时视为重复发布（仅确保 status=0，兼容存量直接上线的智能体）。
     */
    public void publish(Long id, Long tenantId) {
        long world = tenantId == null ? MseConstants.PLATFORM_TENANT_ID : tenantId;
        AiAgent agent = getById(id);
        if (agent.getTenantId() == null || agent.getTenantId() != world) {
            throw new ServiceException(403, "无权发布该智能体");
        }
        String draft = agent.getDraftGraphJson();
        if (draft == null || draft.isBlank()) {
            if (agent.getGraphJson() == null || agent.getGraphJson().isBlank()) {
                throw new ServiceException(400, "该智能体尚未编排画布，请先在编辑器保存草稿");
            }
        } else {
            vip.mseapp.ai.workflow.model.FlowGraph graph =
                    vip.mseapp.ai.workflow.model.FlowGraph.parse(draft);
            if (graph == null) {
                throw new ServiceException(400, "草稿编排图为空，请先在编辑器保存");
            }
            graph.requireValid();
            agent.setGraphJson(draft);
        }
        agent.setStatus(0);
        TenantContext.runAs(world, () -> agentMapper.updateById(agent));
    }

    public void delete(Long id, Long tenantId) {
        long world = tenantId == null ? MseConstants.PLATFORM_TENANT_ID : tenantId;
        AiAgent agent = getById(id);
        if (agent.getTenantId() == null || agent.getTenantId() != world) {
            throw new ServiceException(403, "无权删除该智能体");
        }
        TenantContext.runAs(world, () -> agentMapper.deleteById(id));
    }

    /** 当前租户（供控制器取上下文，便于单测/异步透传） */
    public Long currentTenant() {
        return TenantContext.getRequiredTenantId();
    }
}
