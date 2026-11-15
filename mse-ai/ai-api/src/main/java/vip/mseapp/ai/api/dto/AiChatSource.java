package vip.mseapp.ai.api.dto;

import java.io.Serializable;

/**
 * AI 对话引用来源（RPC 契约，知识库 RAG 命中条目）。
 *
 * <p>与 SSE 通道的 sources 事件同口径：agent 绑定知识库且检索启用时返回，
 * 未绑定知识库/工作流编排智能体返回 null。
 */
public class AiChatSource implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 引用序号（从 1 开始，与回复内引用标记对应） */
    private Integer index;

    /** 命中切片 ID */
    private String chunkId;

    /** 来源文件名 */
    private String fileName;

    /** 相关性得分 */
    private Double score;

    /** 切片摘要（前 200 字） */
    private String excerpt;

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public void setExcerpt(String excerpt) {
        this.excerpt = excerpt;
    }
}
