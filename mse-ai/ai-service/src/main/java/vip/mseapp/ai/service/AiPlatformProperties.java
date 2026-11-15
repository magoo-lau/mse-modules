package vip.mseapp.ai.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 平台配置（mse.ai.*，与 common-ai 的总开关共用前缀、属性类独立）。
 */
@Component
@ConfigurationProperties(prefix = "mse.ai")
public class AiPlatformProperties {

    /** 敏感字段（模型 apiKey / 开放 API Key）AES-GCM 落库加密密钥（生产必须修改） */
    private String cryptKey = "mse-ai-default-crypt-key-please-change";

    /** SSE 对话超时毫秒（超时未完成强制完成 emitter） */
    private long sseTimeoutMillis = 300_000L;

    /** RPC 工具泛化调用超时毫秒 */
    private long rpcToolTimeoutMillis = 60_000L;

    /** 单回合单工具最大调用次数（模型跑飞保护；超限回传提示让模型自行收尾，不再无限重试） */
    private int toolMaxCallsPerTool = 10;

    /** 单回合全部工具合计最大调用次数 */
    private int toolMaxTotalCalls = 30;

    /** 是否启用 Milvus 向量库（知识库 RAG 向量化与检索；未启用时向量相关操作报业务异常） */
    private boolean milvusEnabled = false;

    /** MCP Server 开关（把平台工具/智能体暴露为 MCP；ai-boot 引入 mcp-server starter 后生效） */
    private boolean mcpServerEnabled = false;

    /** 对话历史上下文窗口（最近 N 条消息送入模型） */
    private int memoryWindow = 40;

    /** 对话模型瞬态错误（5xx/429/网络 IO）额外重试次数（0=不重试；重试耗尽且配置了备用模型则切换备用） */
    private int chatRetryTimes = 2;

    /** 对话模型重试退避毫秒（两次尝试之间的固定间隔；流式首 token 前生效，已吐内容后不再重试） */
    private long chatRetryBackoffMillis = 500;

    /**
     * 对话模型单次调用读超时毫秒：非流式调用=等待完整生成的上限；流式调用=相邻数据块间的空闲上限
     * （不限制整体时长，长流式对话不受影响）。SDK 默认 read 随 request 兜底 10 分钟过长——
     * 一次上游挂起即可长期占用服务线程，叠加重试/备用切换后更甚。
     */
    private long chatTimeoutMillis = 120_000L;

    /** Milvus 地址与端口（milvus-enabled=true 时生效） */
    private String milvusHost = "localhost";

    private int milvusPort = 19530;

    /**
     * 旧版全局向量模型（兼容保留）：mse.ai.embedding-model-id 指向的 ai_model 行
     * （该行向量字段在拆行升级后保留）。知识库向量模型解析优先级：知识库绑定
     * embedding_model_id > 本配置 > ai_model 默认向量模型行（is_default=1）。
     * 该指向的模型沿用旧全局集合 mse_ai_kb_v2（存量向量零迁移）；推荐在模型中心
     * 设好默认向量模型后删除本配置。
     */
    private Long embeddingModelId;

    /** 旧版全局向量维度（仅旧集合 mse_ai_kb_v2 兼容参考；新集合建集合时按模型实测维度） */
    private int embeddingDimensions = 1024;

    /** 知识库原文件归档目录（重新解析/重建索引的数据来源） */
    private String kbStoragePath = System.getProperty("user.home") + "/.mse/ai-kb";

    /** 智能体头像本地兜底目录（mse-fs 未引入/未部署时落盘用；mse-fs 模式不经此目录） */
    private String avatarStoragePath = System.getProperty("user.home") + "/.mse/ai-avatar";

    /** 知识库异步解析线程池大小（并发解析文档数） */
    private int kbParseWorkers = 2;

    /**
     * 知识库切片索引增强（自动关键词/自动问题）所用对话模型（ai_model.id，modelType=chat；
     * 空 = 回退 spring.ai.* 装配的默认模型）。仅 kb.enhance-enabled=1 的知识库解析时调用。
     */
    private Long kbEnhanceModelId;

    /** rerank 重排模型（OpenAI 兼容 /rerank 协议，平台级配置；未启用时检索融合后直接返回） */
    private final Rerank rerank = new Rerank();

    public Rerank getRerank() {
        return rerank;
    }

    /** rerank 配置段（mse.ai.rerank.*） */
    public static class Rerank {

        /** 是否启用重排（启用时要求 base-url 与 model 非空） */
        private boolean enabled = false;

        /** rerank 端点（如 https://api.siliconflow.cn/v1） */
        private String baseUrl;

        /** API Key（Bearer） */
        private String apiKey;

        /** 重排模型名（如 BAAI/bge-reranker-v2-m3） */
        private String model;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public String getKbStoragePath() {
        return kbStoragePath;
    }

    public void setKbStoragePath(String kbStoragePath) {
        this.kbStoragePath = kbStoragePath;
    }

    public String getAvatarStoragePath() {
        return avatarStoragePath;
    }

    public void setAvatarStoragePath(String avatarStoragePath) {
        this.avatarStoragePath = avatarStoragePath;
    }

    public int getKbParseWorkers() {
        return kbParseWorkers;
    }

    public void setKbParseWorkers(int kbParseWorkers) {
        this.kbParseWorkers = kbParseWorkers;
    }

    public Long getKbEnhanceModelId() {
        return kbEnhanceModelId;
    }

    public void setKbEnhanceModelId(Long kbEnhanceModelId) {
        this.kbEnhanceModelId = kbEnhanceModelId;
    }

    public String getCryptKey() {
        return cryptKey;
    }

    public void setCryptKey(String cryptKey) {
        this.cryptKey = cryptKey;
    }

    public long getSseTimeoutMillis() {
        return sseTimeoutMillis;
    }

    public void setSseTimeoutMillis(long sseTimeoutMillis) {
        this.sseTimeoutMillis = sseTimeoutMillis;
    }

    public long getRpcToolTimeoutMillis() {
        return rpcToolTimeoutMillis;
    }

    public void setRpcToolTimeoutMillis(long rpcToolTimeoutMillis) {
        this.rpcToolTimeoutMillis = rpcToolTimeoutMillis;
    }

    public int getToolMaxCallsPerTool() {
        return toolMaxCallsPerTool;
    }

    public void setToolMaxCallsPerTool(int toolMaxCallsPerTool) {
        this.toolMaxCallsPerTool = toolMaxCallsPerTool;
    }

    public int getToolMaxTotalCalls() {
        return toolMaxTotalCalls;
    }

    public void setToolMaxTotalCalls(int toolMaxTotalCalls) {
        this.toolMaxTotalCalls = toolMaxTotalCalls;
    }

    public boolean isMilvusEnabled() {
        return milvusEnabled;
    }

    public void setMilvusEnabled(boolean milvusEnabled) {
        this.milvusEnabled = milvusEnabled;
    }

    public boolean isMcpServerEnabled() {
        return mcpServerEnabled;
    }

    public void setMcpServerEnabled(boolean mcpServerEnabled) {
        this.mcpServerEnabled = mcpServerEnabled;
    }

    public int getMemoryWindow() {
        return memoryWindow;
    }

    public void setMemoryWindow(int memoryWindow) {
        this.memoryWindow = memoryWindow;
    }

    public int getChatRetryTimes() {
        return chatRetryTimes;
    }

    public void setChatRetryTimes(int chatRetryTimes) {
        this.chatRetryTimes = chatRetryTimes;
    }

    public long getChatRetryBackoffMillis() {
        return chatRetryBackoffMillis;
    }

    public void setChatRetryBackoffMillis(long chatRetryBackoffMillis) {
        this.chatRetryBackoffMillis = chatRetryBackoffMillis;
    }

    public long getChatTimeoutMillis() {
        return chatTimeoutMillis;
    }

    public void setChatTimeoutMillis(long chatTimeoutMillis) {
        this.chatTimeoutMillis = chatTimeoutMillis;
    }

    public String getMilvusHost() {
        return milvusHost;
    }

    public void setMilvusHost(String milvusHost) {
        this.milvusHost = milvusHost;
    }

    public int getMilvusPort() {
        return milvusPort;
    }

    public void setMilvusPort(int milvusPort) {
        this.milvusPort = milvusPort;
    }

    public Long getEmbeddingModelId() {
        return embeddingModelId;
    }

    public void setEmbeddingModelId(Long embeddingModelId) {
        this.embeddingModelId = embeddingModelId;
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public void setEmbeddingDimensions(int embeddingDimensions) {
        this.embeddingDimensions = embeddingDimensions;
    }
}
