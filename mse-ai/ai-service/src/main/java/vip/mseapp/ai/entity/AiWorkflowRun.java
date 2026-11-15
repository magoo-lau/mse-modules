package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 工作流运行历史：每次编排执行（对话/调试/RPC/开放 API 通道）收口时落一条，
 * 轨迹快照存 traces_json（与 SSE node 事件同构，供前端时间线回放）。
 *
 * <p>tenant_id 显式取自 FlowRunEnv（记录发生在编排线程池，无请求上下文）；
 * 对话/RPC 行按租户隔离可见，平台调试行 tenant_id=0。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_workflow_run")
public class AiWorkflowRun extends BaseEntity {

    /** 运行成功 */
    public static final String STATUS_SUCCESS = "success";
    /** 运行失败（failfast 节点错误 / 超时 / 引擎异常） */
    public static final String STATUS_FAILED = "failed";
    /** 用户取消 / 连接断开联动取消 */
    public static final String STATUS_CANCELLED = "cancelled";

    /** 智能体编码（跨租户唯一） */
    private String agentCode;

    /** 智能体名称（快照，防后续改名影响历史展示） */
    private String agentName;

    /** 触发通道：gateway 对话 / debug 编辑器试运行 / rpc 无状态 / openapi 开放接口 */
    private String channel;

    /** 会话号（debug 无会话为空） */
    private String sessionId;

    /** 运行结果：success / failed / cancelled */
    private String status;

    /** 用户输入（截断存档） */
    private String queryText;

    /** 最终回答（截断存档） */
    private String answer;

    /** 失败原因（status=failed 时） */
    private String error;

    /** 总耗时毫秒 */
    private Long elapsedMs;

    /** 节点轨迹条数 */
    private Integer nodeTotal;

    /** 节点轨迹快照 JSON（NodeTrace 列表，截断存档） */
    private String tracesJson;
}
