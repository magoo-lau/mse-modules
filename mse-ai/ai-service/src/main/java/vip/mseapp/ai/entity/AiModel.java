package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 模型配置（租户自管：各会话世界自有行随世界路由，租户会话=本租户行、平台会话=平台世界行 tenant_id=0；
 * 运行期按主键读取随环境上下文路由，世界未命中回退平台世界行——见 AiModelService/AiModelFactory）。
 * 存量平台公共行已经每租户播种副本（V20260926_02_ai__tenant_seed.sql），
 * 旧「ai_model_tenant 圈定 + ai_model_tenant_config 覆盖」机制已退役。
 *
 * <p>模型类型 model_type：chat（对话模型，供智能体/工作流绑定）、embedding（向量模型，
 * 供知识库 RAG 绑定）与 rerank（重排模型，供知识库精排绑定），一行只表达一种能力，使用方按类型选择。
 * 接口类型 protocol：openai-chat（OpenAI 兼容 chat/completions，DeepSeek /
 * 智谱 GLM / Kimi / 通义千问 / 讯飞星火 / Ollama 等）、anthropic-messages（Claude 原生 /v1/messages，
 * 兼容 DeepSeek/GLM/Kimi 的 Anthropic 兼容端点；向量/重排模型行固定 openai-chat）。
 * Spring AI 2.x 按配置动态构建模型实例。apiKey 落库为 AES-GCM 密文，接口不回显。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_model")
public class AiModel extends BaseEntity {

    /** 接口类型：OpenAI 兼容 Chat Completions */
    public static final String PROTOCOL_OPENAI_CHAT = "openai-chat";
    /** 接口类型：Claude 原生 Messages */
    public static final String PROTOCOL_ANTHROPIC_MESSAGES = "anthropic-messages";

    /** 模型类型：对话模型（智能体/工作流绑定） */
    public static final String MODEL_TYPE_CHAT = "chat";
    /** 模型类型：向量模型（知识库 RAG 绑定） */
    public static final String MODEL_TYPE_EMBEDDING = "embedding";
    /** 模型类型：重排模型（知识库精排绑定，OpenAI 兼容 /rerank 协议） */
    public static final String MODEL_TYPE_RERANK = "rerank";

    /** 模型名称（展示用） */
    private String name;

    /** 供应商标注（deepseek/zhipu/moonshot/dashscope/openai/anthropic/ollama/xfyun/openai-compat，仅展示与前端预设联动） */
    private String provider;

    /** 接口类型（openai-chat / anthropic-messages，供应商预设自动带出，可手改；向量模型行固定 openai-chat） */
    private String protocol;

    /** 模型类型（chat 对话模型 / embedding 向量模型，一行只表达一种能力） */
    private String modelType;

    /** 默认向量模型标记（仅 modelType=embedding 有意义，全局唯一） */
    private Integer isDefault;

    /** 模型端点（openai-chat 自带版本前缀如 https://api.deepseek.com；anthropic-messages 如 https://api.anthropic.com） */
    private String baseUrl;

    /** API Key 密文（本地 Ollama 可为任意非空占位） */
    private String apiKeyEnc;

    /** 模型名（chat=对话模型名 / rerank=重排模型名；embedding 行为空串） */
    private String chatModel;

    /** 向量模型名（modelType=embedding 时为本行向量模型名；chat 行为旧版遗留字段，仅旧数据兼容读取） */
    private String embeddingModel;

    /** 向量模型独立端点（可选，为空复用 baseUrl） */
    private String embeddingBaseUrl;

    /** 向量模型独立 API Key 密文（可选，为空复用 apiKeyEnc） */
    private String embeddingApiKeyEnc;

    /** 默认温度 */
    private Double temperature;

    /** 默认 topP */
    private Double topP;

    /** 默认最大输出 tokens */
    private Integer maxTokens;

    /** 备用对话模型（ai_model.id，model_type=chat；主模型瞬态错误重试仍失败时自动切换，空=不切换） */
    private Long failoverModelId;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    /** 最近连通性测试时间 */
    private java.time.LocalDateTime lastTestTime;

    /** 最近测试结果：0 失败 1 成功 */
    private Integer lastTestOk;

    /** 最近测试失败原因 */
    private String lastTestMsg;

    /** 备注 */
    private String remark;
}
