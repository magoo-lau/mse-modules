package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.core.annotation.TenantDatasource;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 知识库切片（租户业务表）：正文落 MySQL（可审计可重建），向量入 Milvus；
 * id 即向量库 Document id（雪花唯一），双向可追溯。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantDatasource
@TableName("ai_kb_chunk")
public class AiKbChunk extends BaseEntity {

    /** 知识库 ID */
    private Long kbId;

    /** 文档 ID */
    private Long documentId;

    /** 切片序号（文档内顺序） */
    private Integer seq;

    /** 切片正文 */
    private String content;

    /** 索引增强·自动关键词（JSON 数组串；空=未增强，人工切片/降级为空） */
    private String keywords;

    /** 索引增强·自动问题（JSON 数组串；重建索引时反解析复嵌，不重复调用 LLM） */
    private String questions;
}
