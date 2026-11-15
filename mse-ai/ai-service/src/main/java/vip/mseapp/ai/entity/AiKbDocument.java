package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.core.annotation.TenantDatasource;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 知识库文档（租户业务表）：上传解析的处理单元与状态跟踪。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantDatasource
@TableName("ai_kb_document")
public class AiKbDocument extends BaseEntity {

    /** 知识库 ID */
    private Long kbId;

    /** 文件名 */
    private String fileName;

    /** 文件类型（pdf/docx/md/txt/html…，由扩展名判定，解析交给 Tika） */
    private String fileType;

    /** 文件大小（字节） */
    private Long sizeBytes;

    /** 原文件归档路径（支撑重新解析/重建索引，空表示未归档） */
    private String storagePath;

    /** 状态：0 待处理 1 处理中 2 完成 3 失败 */
    private Integer status;

    /** 切片数量 */
    private Integer chunkCount;

    /** 失败原因 */
    private String errorMsg;
}
