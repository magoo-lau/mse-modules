package vip.mseapp.ai.workflow.exec;

import org.springframework.stereotype.Component;
import vip.mseapp.ai.kb.KbRetrievalService;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索节点：渲染查询模板后走平台统一检索管线（KbRetrievalService——
 * 混合召回 + rerank + 阈值过滤 + 文件名补全，租户收口），与智能体 RAG 同源。
 */
@Component
public class KnowledgeNodeExecutor implements NodeExecutor {

    private static final int DEFAULT_TOP_K = 4;

    private final KbRetrievalService retrievalService;

    public KnowledgeNodeExecutor(KbRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public String type() {
        return "knowledge";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        String query = VarRenderer.render(node.str("query", "{{sys.query}}"), ctx.resolver());
        if (query == null || query.isBlank()) {
            throw new ServiceException(400, "节点「" + node.title() + "」检索问题为空");
        }
        List<Long> kbIds = Arrays.stream(node.str("kbIds", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
        if (kbIds.isEmpty()) {
            throw new ServiceException(400, "节点「" + node.title() + "」未配置知识库");
        }
        int topK = node.intg("topK", DEFAULT_TOP_K);
        Double threshold = node.dbl("minScore", null);
        KbRetrievalService.RetrievalResult result =
                retrievalService.retrieve(env.tenantId(), kbIds, query, topK, threshold);

        List<Map<String, Object>> chunks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (KbRetrievalService.KbHit hit : result.hits()) {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("content", hit.content());
            chunk.put("source", hit.fileName() == null ? "" : hit.fileName());
            chunk.put("score", hit.score());
            chunks.add(chunk);
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(hit.content());
        }
        ctx.setOutput(node.id(), "result", text.toString());
        ctx.setOutput(node.id(), "chunks", chunks);
    }
}
