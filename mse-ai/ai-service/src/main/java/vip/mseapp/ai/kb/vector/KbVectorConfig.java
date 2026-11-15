package vip.mseapp.ai.kb.vector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vip.mseapp.ai.service.AiModelFactory;
import vip.mseapp.ai.service.AiPlatformProperties;

/**
 * 知识库向量层装配（mse.ai.milvus-enabled=true 才创建 KbVectorClient）。
 *
 * <p>不用 starter 自动装配：未部署 Milvus 时保持服务可正常启动（RAG 功能降级提示）。
 * 向量模型按知识库绑定（ai_knowledge_base.embedding_model_id）或平台默认向量模型
 * （ai_model.is_default，兼容旧版 mse.ai.embedding-model-id 配置）在运行期解析，
 * 按模型分集合路由（旧配置指向模型沿用 mse_ai_kb_v2，存量向量零迁移）。
 */
@Configuration
@ConditionalOnProperty(prefix = "mse.ai", name = "milvus-enabled", havingValue = "true")
public class KbVectorConfig {

    @Bean(destroyMethod = "close")
    public KbVectorClient kbVectorClient(AiPlatformProperties properties, AiModelFactory modelFactory) {
        return new KbVectorClient(properties, modelFactory);
    }
}
