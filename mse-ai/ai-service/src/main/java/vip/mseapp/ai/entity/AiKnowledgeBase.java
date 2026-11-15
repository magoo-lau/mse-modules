package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.core.annotation.TenantDatasource;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 知识库（租户业务表）：文档集与向量检索范围；向量侧以 metadata.kbId 过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantDatasource
@TableName("ai_knowledge_base")
public class AiKnowledgeBase extends BaseEntity {

    /** 名称 */
    private String name;

    /**
     * 知识库编码（声明库壳通道）：业务积木经 AiKbProvider 声明 + 智能体 kbCodes 引用，
     * 各租户世界按编码幂等建壳；手工创建的库该列为空（唯一键允许多 NULL 不受影响）。
     */
    private String code;

    /** 描述 */
    private String description;

    /** 切片目标大小（token） */
    private Integer chunkSize;

    /** 切片方法：general 通用 / qa 问答对 / table 表格 / book 书籍(标题层级) / one 整篇 */
    private String chunkMethod;

    /** 切片重叠大小（token，general/book 方法生效） */
    private Integer chunkOverlap;

    /** 最大检索条数（检索增强/测试默认值） */
    private Integer topK;

    /** 相似度阈值（0~1，低于该值的召回结果被过滤） */
    private Double similarityThreshold;

    /** 绑定向量模型（ai_model.id，modelType=embedding；空=按旧版全局配置/平台默认向量模型解析） */
    private Long embeddingModelId;

    /** 绑定重排模型（ai_model.id，modelType=rerank；空=按旧版平台级配置解析，均未配置则不重排） */
    private Long rerankModelId;

    /** 切片索引增强（LLM 自动关键词/自动问题）：0 关 1 开（解析入库时逐切片生成，问题前置进嵌入文本） */
    private Integer enhanceEnabled;

    /** 索引增强每个切片生成的自动问题数（1~5，默认 3；空按 3 处理） */
    private Integer enhanceQuestions;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
