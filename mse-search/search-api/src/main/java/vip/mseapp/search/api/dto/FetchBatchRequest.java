package vip.mseapp.search.api.dto;

import java.io.Serializable;

/**
 * 增量拉取请求（mse-search → 业务 Provider，机器语义 JSON 结构）。
 */
public class FetchBatchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sourceCode;

    /** 本批拉取的目标租户（扫描按「租户 × 源」执行；实现必须按此过滤数据） */
    private Long tenantId;

    /** 当前分片号（0 起） */
    private int shardIndex;

    /** 总分片数 */
    private int shardCount;

    /** 增量水位下界（增量列 > watermark；首次全量为 "0"） */
    private String watermark;

    /** 本批最大条数 */
    private int batchSize;

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public int getShardIndex() {
        return shardIndex;
    }

    public void setShardIndex(int shardIndex) {
        this.shardIndex = shardIndex;
    }

    public int getShardCount() {
        return shardCount;
    }

    public void setShardCount(int shardCount) {
        this.shardCount = shardCount;
    }

    public String getWatermark() {
        return watermark;
    }

    public void setWatermark(String watermark) {
        this.watermark = watermark;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
