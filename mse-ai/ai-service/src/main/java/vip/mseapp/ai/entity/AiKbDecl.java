package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 知识库声明目录（平台全局目录）：业务积木经 AiKbProvider 自声明（单体本地扫描 source=local /
 * 微服务推送 source=rpc，零 SQL 种子）的「库壳」编码目录。目录=全局目录，仅平台行（tenant_id=0），
 * 读取走 runIgnore，不建租户副本；各租户世界的库壳实体（ai_knowledge_base.code 同码行）由
 * 智能体副本懒补齐时按本目录幂等创建，文档内容由各租户自行上传维护。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_kb_decl")
public class AiKbDecl extends BaseEntity {

    /** 知识库编码（世界内唯一，如 oa-policy；智能体 kbCodes 引用此编码） */
    private String code;

    /** 名称（各租户库壳创建时的展示名） */
    private String name;

    /** 描述（管理页展示） */
    private String description;

    /** 来源：local 单体声明登记 / rpc 微服务推送登记 */
    private String source;

    /** 声明提供方服务名（source=rpc 时登记，防同码互相覆盖） */
    private String rpcGroup;

    /** 备注 */
    private String remark;
}
