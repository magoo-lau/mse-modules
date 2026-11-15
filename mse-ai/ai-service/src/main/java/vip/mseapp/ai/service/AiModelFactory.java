package vip.mseapp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.entity.AiModel;
import vip.mseapp.ai.mapper.AiModelMapper;
import vip.mseapp.ai.support.AiCrypt;
import vip.mseapp.ai.support.OpenAiCompatInterceptor;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态模型工厂：按 ai_model 配置实例化并缓存 Spring AI 模型。模型行为各会话世界自有
 * （租户自管，见 {@link AiModelService}），实例缓存 key 为 modelId——id 由世界内绑定关系
 * 解析而来，天然世界自洽。配置变更经 {@link #invalidate(Long)} 失效重建。
 *
 * <p>接口类型 {@code protocol} 二选一：openai-chat（OpenAI 兼容 Chat Completions，
 * Spring AI 2.x 经官方 openai-java 客户端连接，在 baseUrl 后追加 /chat/completions，
 * DeepSeek / 智谱 / Kimi / 通义 / 讯飞星火 / Ollama 等兼容端点一套代码全兼容）；anthropic-messages
 * （Claude 原生 Messages，经官方 anthropic-java 客户端追加 /v1/messages，兼容
 * DeepSeek/GLM/Kimi 的 Anthropic 兼容端点）。模型类型区分：对话模型（modelType=chat）与
 * 向量模型（modelType=embedding）各自独立建档、按类型校验取用；向量模型仅支持 OpenAI 兼容协议，
 * 旧版 chat 行上的 embedding_* 遗留字段仍可兼容构建向量模型（未拆分数据）。
 */
@Component
public class AiModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AiModelFactory.class);

    private final AiModelMapper modelMapper;
    private final AiCrypt crypt;
    private final ObjectProvider<ChatModel> defaultChatModel;
    private final AiPlatformProperties properties;

    /** key：modelId（各世界自有行，id 全局不自交） */
    private final Map<String, ChatModel> chatCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingCache = new ConcurrentHashMap<>();

    public AiModelFactory(AiModelMapper modelMapper, AiCrypt crypt,
                          ObjectProvider<ChatModel> defaultChatModel, AiPlatformProperties properties) {
        this.modelMapper = modelMapper;
        this.crypt = crypt;
        this.defaultChatModel = defaultChatModel;
        this.properties = properties;
    }

    /** 取对话模型：modelId 为空时回退 spring.ai.* 装配的默认模型（零配置可用），仍无则报错 */
    public ChatModel getChatModel(Long modelId) {
        if (modelId == null) {
            ChatModel fallback = defaultChatModel.getIfAvailable();
            if (fallback == null) {
                throw new ServiceException(500,
                        "智能体未绑定对话模型，且服务未装配默认模型（spring.ai.*）；请到「模型中心」选择对话模型绑定到该智能体后重试");
            }
            return fallback;
        }
        return chatCache.computeIfAbsent(String.valueOf(modelId), key -> {
            AiModel cfg = requireModel(modelId);
            if (AiModel.MODEL_TYPE_EMBEDDING.equals(cfg.getModelType())) {
                throw new ServiceException(400, "「" + cfg.getName() + "」是向量模型，不能用于对话；请在智能体/工作流中选择对话模型");
            }
            return wrapWithResilience(modelId, cfg, buildChatModel(cfg));
        });
    }

    /**
     * 织入瞬态错误重试与备用模型切换（{@link ResilientChatModel}，5xx/429/网络 IO 重试，
     * 主模型任意失败（含 401 鉴权类非瞬态错误，备用行持有独立密钥）且配置了备用模型则整体切换）。
     * 备用行按行配置直接构建——failover 深度 1，备用不再嵌套备用（天然防环）；
     * 行未配置备用且重试次数为 0 时原样返回不包装饰器。
     */
    private ChatModel wrapWithResilience(Long modelId, AiModel cfg, ChatModel primary) {
        int retryTimes = Math.max(0, properties.getChatRetryTimes());
        Long failoverId = cfg.getFailoverModelId();
        ChatModel backup = null;
        String backupName = null;
        if (failoverId != null && !failoverId.equals(modelId)) {
            try {
                AiModel backupCfg = requireModel(failoverId);
                if (AiModel.MODEL_TYPE_EMBEDDING.equals(backupCfg.getModelType())
                        || AiModel.MODEL_TYPE_RERANK.equals(backupCfg.getModelType())) {
                    log.warn("[mse-ai] 备用模型 {} 不是对话模型，忽略 failover: model={}", failoverId, modelId);
                } else {
                    backup = buildChatModel(backupCfg);
                    backupName = backupCfg.getName();
                }
            } catch (Exception e) {
                // 备用行被删/库不可达等：降级为主模型单跑，不阻塞主链路
                log.warn("[mse-ai] 备用模型解析失败，忽略 failover: model={}, failoverId={}, reason={}",
                        modelId, failoverId, e.getMessage());
            }
        }
        if (retryTimes == 0 && backup == null) {
            return primary;
        }
        return new ResilientChatModel(primary, backup, cfg.getName(), backupName, retryTimes,
                Math.max(0, properties.getChatRetryBackoffMillis()));
    }

    /** 按接口类型构建对话模型 */
    private ChatModel buildChatModel(AiModel cfg) {
        ToolCallingManager toolCallingManager = buildToolCallingManager();
        if (AiModel.PROTOCOL_ANTHROPIC_MESSAGES.equals(cfg.getProtocol())) {
            return AnthropicChatModel.builder()
                    .options(AnthropicChatOptions.builder()
                            .baseUrl(cfg.getBaseUrl())
                            .apiKey(crypt.decrypt(cfg.getApiKeyEnc()))
                            .model(cfg.getChatModel())
                            .temperature(cfg.getTemperature())
                            .topP(cfg.getTopP())
                            .maxTokens(cfg.getMaxTokens())
                            .build())
                    .toolCallingManager(toolCallingManager)
                    .httpClientBuilderCustomizer(this::applyChatReadTimeout)
                    .build();
        }
        // openai-chat：Spring AI 2.x 经官方 openai-java 客户端，在 baseUrl 后追加 /chat/completions；
        // 注入响应归一化垫片（星火等端点成功响应缺 OpenAI 规范必填字段，见 OpenAiCompatInterceptor）
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(crypt.decrypt(cfg.getApiKeyEnc()))
                .model(cfg.getChatModel())
                .temperature(cfg.getTemperature())
                .topP(cfg.getTopP())
                .maxTokens(cfg.getMaxTokens())
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .toolCallingManager(toolCallingManager)
                .httpClientBuilderCustomizer(this::applyChatReadTimeout)
                .httpClientBuilderCustomizer(builder -> builder.interceptor(new OpenAiCompatInterceptor()))
                .build();
    }

    /**
     * 对话模型单次调用读超时（mse.ai.chat-timeout-millis，缺省 120s）：非流式调用=等待完整生成的
     * 上限（原 SDK 默认随 request 兜底 10 分钟，一次上游挂起即长期占用服务线程）；流式调用=相邻
     * 数据块间空闲上限（token 持续到达不误伤长对话）。刻意不设 request（整体）超时——多轮工具
     * 调用与长流式对话的整体时长不可预估。两个官方 SDK 的 Timeout 构建器同构，但类型无公共接口，
     * 经重载分派（方法引用按目标类型自行匹配）。
     */
    private void applyChatReadTimeout(
            org.springframework.ai.anthropic.http.okhttp.SpringAiAnthropicHttpClient.Builder builder) {
        builder.timeout(com.anthropic.core.Timeout.builder()
                .read(Duration.ofMillis(Math.max(1_000L, properties.getChatTimeoutMillis())))
                .build());
    }

    private void applyChatReadTimeout(
            org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient.Builder builder) {
        builder.timeout(com.openai.core.Timeout.builder()
                .read(Duration.ofMillis(Math.max(1_000L, properties.getChatTimeoutMillis())))
                .build());
    }

    /**
     * 工具调用管理器：收紧单回合调用上限（Spring AI 默认单工具 40 次/合计 150 次过于宽松，
     * 模型参数抄错时会反复道歉重试数十轮才被拦住），超限以「已达上限」的工具响应回传，
     * 让模型自行收尾作答（RETURN_ERROR_RESPONSE 优于抛异常中断整个流）。
     * 构建口径抽到 {@link FriendlyToolCallingManager#fromProperties}——与 spring.ai.*
     * 兜底模型的自动装配 Bean 共用（见 AiServiceAutoConfiguration），两处不漂移。
     */
    private ToolCallingManager buildToolCallingManager() {
        return FriendlyToolCallingManager.fromProperties(properties);
    }

    /**
     * 取向量模型（OpenAI 兼容协议）：向量模型行（modelType=embedding）直接用本行端点/Key，
     * 向量模型名在 embedding_model 字段；旧版 chat 行上的 embedding_* 遗留字段（独立端点/Key）
     * 仍兼容读取。未配置向量模型报错。
     */
    public EmbeddingModel getEmbeddingModel(Long modelId) {
        if (modelId == null) {
            throw new ServiceException(500, "未指定模型，无法构建向量模型");
        }
        return embeddingCache.computeIfAbsent(String.valueOf(modelId), key -> {
            AiModel cfg = requireModel(modelId);
            boolean embeddingRow = AiModel.MODEL_TYPE_EMBEDDING.equals(cfg.getModelType());
            if (cfg.getEmbeddingModel() == null || cfg.getEmbeddingModel().isBlank()) {
                throw new ServiceException(500, embeddingRow
                        ? "向量模型配置不完整（缺少向量模型名）：" + cfg.getName()
                        : "「" + cfg.getName() + "」未配置向量能力；请在模型管理新建向量模型并绑定到知识库");
            }
            // 向量模型行直接用本行端点/Key（忽略遗留向量字段）；旧版 chat 行沿用独立向量端点配置
            String baseUrl = !embeddingRow && cfg.getEmbeddingBaseUrl() != null && !cfg.getEmbeddingBaseUrl().isBlank()
                    ? cfg.getEmbeddingBaseUrl() : cfg.getBaseUrl();
            String apiKey = !embeddingRow && cfg.getEmbeddingApiKeyEnc() != null && !cfg.getEmbeddingApiKeyEnc().isBlank()
                    ? crypt.decrypt(cfg.getEmbeddingApiKeyEnc()) : crypt.decrypt(cfg.getApiKeyEnc());
            OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .model(cfg.getEmbeddingModel())
                    .build();
            return OpenAiEmbeddingModel.builder()
                    .options(options)
                    .httpClientBuilderCustomizer(builder -> builder.interceptor(new OpenAiCompatInterceptor()))
                    .build();
        });
    }

    /** 配置变更后失效缓存（该模型全部实例变体 + 把它配为备用模型的引用行，下次取用重建） */
    public void invalidate(Long modelId) {
        invalidateKeys(modelId);
        // 联动失效：合成实例持有按备用行直接构建的实例，备用行配置变化时引用行缓存一并失效
        //（failover 引用经每租户播种按世界改写、世界内自洽——当前世界清查即可；缓存 key 为全局 modelId）
        List<Long> dependentIds = TenantContext.runAs(AiModelService.worldId(), () -> modelMapper.selectList(
                        new LambdaQueryWrapper<AiModel>().select(AiModel::getId)
                                .eq(AiModel::getFailoverModelId, modelId)))
                .stream().map(AiModel::getId).toList();
        dependentIds.forEach(this::invalidateKeys);
        log.info("[mse-ai] 模型缓存已失效: modelId={}{}", modelId,
                dependentIds.isEmpty() ? "" : "（联动备用引用行 " + dependentIds + "）");
    }

    private void invalidateKeys(Long modelId) {
        String key = String.valueOf(modelId);
        chatCache.keySet().removeIf(k -> k.equals(key));
        embeddingCache.keySet().removeIf(k -> k.equals(key));
    }

    private AiModel requireModel(Long id) {
        // 模型行为各会话世界自有：随环境上下文路由读取（对话运行时/开放 API/KB 解析线程均已有租户上下文）；
        // 世界未命中时回退平台世界行（旧版全局配置 mse.ai.embedding-model-id 指向平台行）
        AiModel cfg = TenantContext.runAs(AiModelService.worldId(), () -> modelMapper.selectById(id));
        if (cfg == null) {
            cfg = TenantContext.runAsPlatform(() -> modelMapper.selectById(id));
        }
        if (cfg == null) {
            throw new ServiceException(500, "模型配置不存在: id=" + id);
        }
        return cfg;
    }
}
