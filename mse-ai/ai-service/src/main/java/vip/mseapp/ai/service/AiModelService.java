package vip.mseapp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.mseapp.ai.entity.AiModel;
import vip.mseapp.ai.mapper.AiModelMapper;
import vip.mseapp.ai.support.AiCrypt;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.context.UserContext;
import vip.mseapp.core.exception.ServiceException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 模型中心（租户自管）：模型配置行按会话世界单边读写——租户会话 = 本租户自有行，
 * 平台会话 = 平台世界行（tenant_id=0）。存量平台公共行已经每租户播种副本
 * （V20260926_02_ai__tenant_seed.sql），不再存在"平台维护全局模型 + 租户圈定/覆盖"模式
 * （ai_model_tenant / ai_model_tenant_config 机制已退役）。
 *
 * <p>读取口径：世界维度查询统一 {@link TenantContext#runAs} 随世界路由（一切皆租户：独立库租户
 * 落专属库、共享租户落绑定共享库、平台世界 0 落平台库/降级主库），显式 tenant_id 条件保留作防御；
 * 按主键的运行期读取（对话/向量实例构建，多在异步线程）经环境上下文路由，世界未命中时回退
 * 平台世界行（旧版全局配置兼容，见 {@link AiModelFactory}）。
 *
 * <p>模型类型区分（model_type）：chat 对话模型 / embedding 向量模型 / rerank 重排模型，
 * 一行只表达一种能力。使用方按类型选择：智能体/工作流绑定对话模型，知识库绑定向量模型
 * （可多行并存，is_default 标记本世界默认向量模型），知识库精排绑定重排模型。
 */
@Service
public class AiModelService {

    private static final Logger log = LoggerFactory.getLogger(AiModelService.class);

    /** 允许的接口类型（与前端供应商预设联动） */
    private static final Set<String> PROTOCOLS = Set.of(
            AiModel.PROTOCOL_OPENAI_CHAT, AiModel.PROTOCOL_ANTHROPIC_MESSAGES);

    private final AiModelMapper modelMapper;
    private final AiModelFactory modelFactory;
    private final AiCrypt crypt;
    private final AiPlatformProperties properties;
    private final org.springframework.beans.factory.ObjectProvider<vip.mseapp.ai.kb.rerank.RerankClient> rerankClient;

    public AiModelService(AiModelMapper modelMapper, AiModelFactory modelFactory,
                          AiCrypt crypt, AiPlatformProperties properties,
                          org.springframework.beans.factory.ObjectProvider<vip.mseapp.ai.kb.rerank.RerankClient> rerankClient) {
        this.modelMapper = modelMapper;
        this.modelFactory = modelFactory;
        this.crypt = crypt;
        this.properties = properties;
        this.rerankClient = rerankClient;
    }

    /** 当前会话世界编号（无上下文按平台世界 0 处理，供异步/RPC 兜底） */
    public static long worldId() {
        Long tenantId = TenantContext.getTenantId();
        return tenantId == null ? MseConstants.PLATFORM_TENANT_ID : tenantId;
    }

    /** 行类型归一（历史数据/防御：空按对话模型处理） */
    private static String typeOf(AiModel model) {
        return model.getModelType() == null || model.getModelType().isBlank()
                ? AiModel.MODEL_TYPE_CHAT : model.getModelType();
    }

    /** 管理分页：本世界自有行 */
    public Page<AiModel> page(long pageNum, long pageSize, String keyword, String modelType) {
        long world = worldId();
        LambdaQueryWrapper<AiModel> wrapper = new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getTenantId, world)
                .orderByDesc(AiModel::getId);
        if (modelType != null && !modelType.isBlank()) {
            wrapper.eq(AiModel::getModelType, modelType);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(AiModel::getName, keyword)
                    .or().like(AiModel::getChatModel, keyword)
                    .or().like(AiModel::getProvider, keyword));
        }
        return TenantContext.runAs(world, () -> modelMapper.selectPage(new Page<>(pageNum, pageSize), wrapper));
    }

    public AiModel getById(Long id) {
        // 随会话世界路由按主键读；归属显式校验兜底他世界行（绑定/详情等请求链路入口）
        AiModel model = TenantContext.runAs(worldId(), () -> modelMapper.selectById(id));
        if (model == null || model.getTenantId() == null || model.getTenantId() != worldId()) {
            throw new ServiceException(404, "模型配置不存在");
        }
        return model;
    }

    /**
     * 新增/修改：归属强制为当前世界；apiKey 仅在传入非空时更新（编辑页不回显明文）。
     * 按模型类型校验：对话模型必须有对话模型名；向量模型必须有向量模型名且接口类型固定
     * openai-chat（anthropic 协议无向量接口）；重排模型必须有重排模型名且接口类型固定 openai-chat
     * （/rerank 兼容端点）；is_default 默认标记仅向量行有效且世界内唯一。
     */
    public Long save(AiModel form, String apiKey, String embeddingApiKey) {
        AiModel model = form.getId() == null ? new AiModel() : getById(form.getId());
        String modelType = form.getModelType() == null || form.getModelType().isBlank()
                ? AiModel.MODEL_TYPE_CHAT : form.getModelType();
        if (!AiModel.MODEL_TYPE_CHAT.equals(modelType) && !AiModel.MODEL_TYPE_EMBEDDING.equals(modelType)
                && !AiModel.MODEL_TYPE_RERANK.equals(modelType)) {
            throw new ServiceException(400, "不支持的模型类型：" + modelType);
        }
        model.setName(form.getName());
        model.setProvider(form.getProvider());
        model.setModelType(modelType);
        String protocol = form.getProtocol() == null || form.getProtocol().isBlank()
                ? AiModel.PROTOCOL_OPENAI_CHAT : form.getProtocol();
        if (AiModel.MODEL_TYPE_EMBEDDING.equals(modelType) || AiModel.MODEL_TYPE_RERANK.equals(modelType)) {
            // 向量/重排模型仅支持 OpenAI 兼容端点
            protocol = AiModel.PROTOCOL_OPENAI_CHAT;
        } else if (!PROTOCOLS.contains(protocol)) {
            throw new ServiceException(400, "不支持的接口类型：" + protocol);
        }
        model.setProtocol(protocol);
        model.setBaseUrl(form.getBaseUrl());
        if (AiModel.MODEL_TYPE_EMBEDDING.equals(modelType)) {
            if (form.getEmbeddingModel() == null || form.getEmbeddingModel().isBlank()) {
                throw new ServiceException(400, "向量模型名不能为空");
            }
            model.setChatModel("");
            model.setEmbeddingModel(form.getEmbeddingModel());
            // 向量模型行不使用对话采样参数
            model.setTemperature(null);
            model.setTopP(null);
            model.setMaxTokens(null);
        } else if (AiModel.MODEL_TYPE_RERANK.equals(modelType)) {
            if (form.getChatModel() == null || form.getChatModel().isBlank()) {
                throw new ServiceException(400, "重排模型名不能为空");
            }
            model.setChatModel(form.getChatModel());
            model.setEmbeddingModel("");
            // 重排模型行不使用对话采样参数
            model.setTemperature(null);
            model.setTopP(null);
            model.setMaxTokens(null);
            model.setIsDefault(0);
        } else {
            if (form.getChatModel() == null || form.getChatModel().isBlank()) {
                throw new ServiceException(400, "对话模型名不能为空");
            }
            model.setChatModel(form.getChatModel());
            model.setTemperature(form.getTemperature());
            model.setTopP(form.getTopP());
            model.setMaxTokens(form.getMaxTokens());
            model.setIsDefault(0);
        }
        // 备用对话模型（仅对话模型行）：非对话行强制清空；对话行校验存在性与类型，不能指向自身
        Long failoverId = form.getFailoverModelId();
        if (!AiModel.MODEL_TYPE_CHAT.equals(modelType) || failoverId == null) {
            model.setFailoverModelId(null);
        } else {
            if (failoverId.equals(model.getId())) {
                throw new ServiceException(400, "备用模型不能是模型自身");
            }
            AiModel backup = TenantContext.runAs(worldId(), () -> modelMapper.selectById(failoverId));
            if (backup == null || backup.getTenantId() == null || backup.getTenantId() != worldId()) {
                throw new ServiceException(400, "备用模型不存在: id=" + failoverId);
            }
            if (!AiModel.MODEL_TYPE_CHAT.equals(typeOf(backup))) {
                throw new ServiceException(400, "「" + backup.getName() + "」不是对话模型，不能作为备用模型");
            }
            model.setFailoverModelId(failoverId);
        }
        model.setStatus(form.getStatus() == null ? 0 : form.getStatus());
        model.setRemark(form.getRemark());
        if (apiKey != null && !apiKey.isBlank()) {
            model.setApiKeyEnc(crypt.encrypt(apiKey));
        }
        if (embeddingApiKey != null && !embeddingApiKey.isBlank()) {
            model.setEmbeddingApiKeyEnc(crypt.encrypt(embeddingApiKey));
        }
        boolean wantDefault = AiModel.MODEL_TYPE_EMBEDDING.equals(modelType)
                && form.getIsDefault() != null && form.getIsDefault() == 1;
        if (AiModel.MODEL_TYPE_EMBEDDING.equals(modelType)) {
            model.setIsDefault(wantDefault ? 1 : 0);
        }
        if (model.getId() == null) {
            model.setTenantId(worldId());
            TenantContext.runAs(worldId(), () -> modelMapper.insert(model));
        } else {
            TenantContext.runAs(worldId(), () -> modelMapper.updateById(model));
        }
        if (wantDefault) {
            clearDefaultEmbeddingFlagExcept(model.getId());
        }
        modelFactory.invalidate(model.getId());
        return model.getId();
    }

    /** 默认向量模型唯一性（世界内）：清掉其他向量行的默认标记（读写同世界路由） */
    private void clearDefaultEmbeddingFlagExcept(Long modelId) {
        TenantContext.runAs(worldId(), () -> {
            modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                            .eq(AiModel::getTenantId, worldId())
                            .eq(AiModel::getModelType, AiModel.MODEL_TYPE_EMBEDDING)
                            .eq(AiModel::getIsDefault, 1))
                    .stream().filter(d -> !d.getId().equals(modelId))
                    .forEach(d -> {
                        d.setIsDefault(0);
                        modelMapper.updateById(d);
                    });
        });
    }

    /** 删除本世界模型行（备用引用联动失效由工厂 invalidate 处理） */
    public void delete(Long id) {
        getById(id);
        TenantContext.runAs(worldId(), () -> modelMapper.deleteById(id));
        modelFactory.invalidate(id);
    }

    /** 连通性测试：对话模型真实发起一次最小对话；向量模型真实 embed 一次并返回维度；重排模型真实重排一次并返回得分 */
    public String test(Long id) {
        AiModel model = getById(id);
        String reply;
        try {
            if (AiModel.MODEL_TYPE_EMBEDDING.equals(typeOf(model))) {
                int dimensions = modelFactory.getEmbeddingModel(id).embed("连通性测试").length;
                updateTestResult(model, 1, null);
                return "向量维度 " + dimensions + "，正常";
            }
            if (AiModel.MODEL_TYPE_RERANK.equals(typeOf(model))) {
                vip.mseapp.ai.kb.rerank.RerankClient client = rerankClient.getIfAvailable();
                if (client == null) {
                    throw new ServiceException(500, "重排组件不可用");
                }
                var scores = client.rerank(rerankEndpointOf(model.getId()), "连通性测试",
                        List.of("这是一条用于连通性测试的候选文档"), 1);
                updateTestResult(model, 1, null);
                return "重排正常，相关性得分 " + (scores.isEmpty() ? "-"
                        : String.format("%.4f", scores.get(0).score()));
            }
            var chatModel = modelFactory.getChatModel(id);
            var prompt = new org.springframework.ai.chat.prompt.Prompt("连通性测试，请只回复两个字：正常");
            reply = chatModel.call(prompt).getResult().getOutput().getText();
        } catch (Exception e) {
            String msg = formatHint(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            updateTestResult(model, 0, msg.length() > 480 ? msg.substring(0, 480) : msg);
            throw new ServiceException(500, "连通性测试失败：" + msg);
        }
        updateTestResult(model, 1, null);
        return reply;
    }

    private void updateTestResult(AiModel model, int ok, String msg) {
        model.setLastTestTime(LocalDateTime.now());
        model.setLastTestOk(ok);
        model.setLastTestMsg(msg);
        TenantContext.runAs(model.getTenantId(), () -> modelMapper.updateById(model));
        log.info("[mse-ai] 模型连通性测试: model={}, result={}", model.getName(), ok == 1 ? "成功" : "失败");
    }

    /** 本世界可用模型列表（供智能体编辑选择；全类型） */
    public List<AiModel> listAvailableForTenant(Long tenantId) {
        return listAvailableForTenant(tenantId, null);
    }

    /**
     * 本世界可用模型列表（按模型类型过滤）。
     *
     * @param modelType chat / embedding / rerank；空 = 不过滤（全量）
     */
    public List<AiModel> listAvailableForTenant(Long tenantId, String modelType) {
        long world = tenantId == null ? MseConstants.PLATFORM_TENANT_ID : tenantId;
        List<AiModel> enabled = TenantContext.runAs(world, () -> modelMapper.selectList(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getTenantId, world)
                        .eq(AiModel::getStatus, 0).orderByDesc(AiModel::getId)));
        if (modelType != null && !modelType.isBlank()) {
            enabled = enabled.stream().filter(m -> modelType.equals(typeOf(m))).toList();
        }
        return enabled;
    }

    // ==================== 向量模型解析（知识库 RAG 用） ====================

    /**
     * 知识库生效向量模型解析链：知识库显式绑定（行存在且具备向量配置）→
     * 旧版全局配置 mse.ai.embedding-model-id（指向行可能已被拆行，向量字段保留）→
     * 本世界默认标记向量行。均不可用返回 null（调用方报「未配置向量模型」）。
     */
    public Long resolveEmbeddingModelId(Long kbBinding) {
        if (kbBinding != null && hasEmbeddingConfig(kbBinding)) {
            return kbBinding;
        }
        Long legacy = properties.getEmbeddingModelId();
        if (legacy != null && hasEmbeddingConfig(legacy)) {
            return legacy;
        }
        return defaultEmbeddingModelId();
    }

    /** 新建知识库等场景的推荐向量模型：本世界默认标记行优先，其次旧版全局配置 */
    public Long resolvePreferredEmbeddingModelId() {
        Long def = defaultEmbeddingModelId();
        if (def != null) {
            return def;
        }
        Long legacy = properties.getEmbeddingModelId();
        return legacy != null && hasEmbeddingConfig(legacy) ? legacy : null;
    }

    /** 本世界默认向量模型（is_default=1 的向量行；未设置返回 null） */
    public Long defaultEmbeddingModelId() {
        return TenantContext.runAs(worldId(), () -> modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getTenantId, worldId())
                        .eq(AiModel::getModelType, AiModel.MODEL_TYPE_EMBEDDING)
                        .eq(AiModel::getIsDefault, 1)
                        .last("LIMIT 1")))
                .stream().findFirst().map(AiModel::getId).orElse(null);
    }

    /** 本世界默认向量模型行（vectorStoreStatus 展示名用；未设置返回 null） */
    public AiModel defaultEmbeddingModel() {
        Long id = defaultEmbeddingModelId();
        return id == null ? null : getById(id);
    }

    /** 行是否具备向量配置（向量行必有向量模型名；旧版 chat 行上的遗留向量字段也算） */
    private boolean hasEmbeddingConfig(Long modelId) {
        AiModel model = readModelWithPlatformFallback(modelId);
        return model != null && model.getEmbeddingModel() != null && !model.getEmbeddingModel().isBlank();
    }

    /**
     * 按主键读模型行：当前世界未命中时回退平台世界行——旧版全局配置
     * mse.ai.embedding-model-id 指向平台世界（tenant_id=0）行，世界内播种副本缺席时兼容。
     */
    private AiModel readModelWithPlatformFallback(Long modelId) {
        AiModel model = TenantContext.runAs(worldId(), () -> modelMapper.selectById(modelId));
        if (model == null) {
            model = TenantContext.runAsPlatform(() -> modelMapper.selectById(modelId));
        }
        return model;
    }

    /** 本世界全部向量模型行 id + 旧版全局配置 id（向量清理兜底遍历候选集合用，去重保序） */
    public List<Long> listEmbeddingCandidateIds() {
        List<Long> ids = new ArrayList<>(TenantContext.runAs(worldId(), () -> modelMapper.selectList(
                        new LambdaQueryWrapper<AiModel>().eq(AiModel::getTenantId, worldId())
                                .eq(AiModel::getModelType, AiModel.MODEL_TYPE_EMBEDDING)))
                .stream().map(AiModel::getId).toList());
        Long legacy = properties.getEmbeddingModelId();
        if (legacy != null && !ids.contains(legacy)) {
            ids.add(legacy);
        }
        return ids;
    }

    /** 设为默认向量模型（仅向量行；本世界唯一默认，覆盖其他行的标记） */
    public void setDefaultEmbedding(Long id) {
        AiModel model = getById(id);
        if (!AiModel.MODEL_TYPE_EMBEDDING.equals(typeOf(model))) {
            throw new ServiceException(400, "仅向量模型可设为默认向量模型");
        }
        clearDefaultEmbeddingFlagExcept(id);
        model.setIsDefault(1);
        modelMapper.updateById(model);
        log.info("[mse-ai] 已设为默认向量模型: model={}, operator={}", model.getName(), UserContext.getUsername());
    }

    // ==================== 重排模型解析（知识库 RAG 用） ====================

    /**
     * 重排端点解析链：知识库显式绑定的 rerank 行（存在、启用、端点/模型名齐备）→
     * 旧版服务级配置 mse.ai.rerank.*（enabled=true）→ null（不重排）。
     */
    public vip.mseapp.ai.kb.rerank.RerankClient.RerankEndpoint resolveRerankEndpoint(Long kbBinding) {
        vip.mseapp.ai.kb.rerank.RerankClient.RerankEndpoint bound =
                kbBinding == null ? null : rerankEndpointOf(kbBinding);
        if (bound != null) {
            return bound;
        }
        AiPlatformProperties.Rerank cfg = properties.getRerank();
        if (cfg.isEnabled() && notBlank(cfg.getBaseUrl()) && notBlank(cfg.getModel())) {
            return new vip.mseapp.ai.kb.rerank.RerankClient.RerankEndpoint(
                    cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(), "服务级配置");
        }
        return null;
    }

    /** rerank 行 → 重排端点（行不存在/类型不符/停用/配置不齐返回 null） */
    private vip.mseapp.ai.kb.rerank.RerankClient.RerankEndpoint rerankEndpointOf(Long modelId) {
        AiModel model = readModelWithPlatformFallback(modelId);
        if (model == null || !AiModel.MODEL_TYPE_RERANK.equals(typeOf(model))) {
            return null;
        }
        if (model.getStatus() == null || model.getStatus() != 0
                || isBlank(model.getBaseUrl()) || isBlank(model.getChatModel())) {
            return null;
        }
        String apiKey = model.getApiKeyEnc() == null || model.getApiKeyEnc().isBlank()
                ? "" : crypt.decrypt(model.getApiKeyEnc());
        return new vip.mseapp.ai.kb.rerank.RerankClient.RerankEndpoint(
                model.getBaseUrl(), apiKey, model.getChatModel(), model.getName());
    }

    /** 校验为重排模型（知识库精排绑定用；类型不符给明确业务错误） */
    public void assertRerankModel(Long modelId) {
        AiModel model = getById(modelId);
        if (!AiModel.MODEL_TYPE_RERANK.equals(typeOf(model))) {
            throw new ServiceException(400, "「" + model.getName() + "」不是重排模型，请选择模型类型为重排模型的模型");
        }
    }

    /** openai-java 字段校验报错（"`xxx` is not set"）= 端点返回了非标准 OpenAI 格式响应，补可操作提示 */
    private static String formatHint(String msg) {
        return msg != null && msg.contains("` is not set")
                ? msg + "（端点返回的不是标准 OpenAI 格式响应，请检查接口类型/端点/模型名配置）"
                : msg;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 校验为对话模型（智能体/工作流绑定用；类型不符给明确业务错误） */
    public void assertChatModel(Long modelId) {
        AiModel model = getById(modelId);
        if (!AiModel.MODEL_TYPE_CHAT.equals(typeOf(model))) {
            throw new ServiceException(400, "「" + model.getName() + "」是向量模型，智能体只能绑定对话模型");
        }
    }

    /** 校验为向量模型（知识库绑定用；类型不符给明确业务错误） */
    public void assertEmbeddingModel(Long modelId) {
        AiModel model = getById(modelId);
        if (!AiModel.MODEL_TYPE_EMBEDDING.equals(typeOf(model))) {
            throw new ServiceException(400, "「" + model.getName() + "」不是向量模型，请选择模型类型为向量模型的模型");
        }
    }
}
