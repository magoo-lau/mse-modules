package vip.mseapp.fs.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 文件元数据（物理对象由 FileStorage 实现保管，本表仅记录归属/检索信息）。
 *
 * <p>继承 BaseEntity 即纳入租户行级隔离：tenant_id 插入时自动填充、查询自动改写——
 * 跨租户的 fileId 查询直接落空（404），业务代码禁止手工赋值 tenantId。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fs_file")
public class FsFile extends BaseEntity {

    /** 原始文件名 */
    private String fileName;

    /** 存储键（{tenantId}/{yyyyMM}/{fileId}.{ext}，首段即租户物理隔离前缀） */
    private String storageKey;

    /** 存储类型：local / rustfs（上传当时的实现，切换存储模式后历史文件仍可按原实现寻址需自行迁移） */
    private String storageType;

    /** 内容 MIME 类型 */
    private String contentType;

    /** 字节数 */
    private Long fileSize;

    /** 业务分类（如 oa/attachment、ai/document，便于按业务检索） */
    private String bizType;

    /** 业务关联 ID（如表单实例 ID） */
    private String bizId;

    /** 访问级别：tenant（默认，登录 + 本租户）/ public（可经 /fs/asset/{tenantId}/{id}.{ext} 匿名读，登录页素材等场景） */
    private String accessLevel;

    /** 内容 MD5（32 位小写 hex；同租户内同 MD5 重复上传秒传，复用已有物理对象） */
    private String contentMd5;

    /** 分片上传会话 ID（仅 rustfs 直传进行中非空；complete/abort 后清空/删行） */
    private String uploadId;
}
