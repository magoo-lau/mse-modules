package vip.mseapp.ai.api;

import java.util.List;

import vip.mseapp.ai.api.dto.AiKbDescriptor;

/**
 * 知识库声明契约（业务积木实现）：容器内 {@code @Component} 声明即可，mse-ai 自动登记
 * 知识库编码目录（单体聚合形态启动扫描直收；微服务 dubbo 模式经推送器周期重推）。
 *
 * <p>与 {@link AiAgentProvider} 同构的声明式自注册：业务模块声明「制度库」这类随租户
 * 自动供给的知识库壳，配合 {@code AiAgentDescriptor.kbCodes}（智能体按编码绑定）实现
 * 「声明智能体 + 声明知识库 → 各租户零配置开箱可用」——库壳在各租户世界按编码幂等创建，
 * 文档内容仍由各租户在知识库管理上传维护（RAG 检索/切片/向量化全部复用 mse-ai 既有链路）。
 */
public interface AiKbProvider {

    /**
     * 声明本模块提供的知识库清单。
     *
     * @return 描述清单（code 必填且世界内唯一；单条不合法仅跳过该条，不影响整批）
     */
    List<AiKbDescriptor> knowledgeBases();
}
