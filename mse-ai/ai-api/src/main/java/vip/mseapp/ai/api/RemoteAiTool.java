package vip.mseapp.ai.api;

import vip.mseapp.ai.api.dto.AiToolDescriptor;
import vip.mseapp.ai.api.dto.AiToolResult;

/**
 * AI 工具契约（反向集成：业务服务实现并导出，智能体经工具调用消费业务能力）。
 *
 * <p>接入方式（微服务形态）：业务服务实现本接口并声明自己的 Bean，再用
 * {@code MseDubboSupport.serviceConfig(...)} 以 <b>group=spring.application.name</b>
 * 导出（多个业务服务同名接口并存，靠 group 区分提供方，mse-ai 泛化调用按 group 定向）：
 * <pre>{@code
 * @Bean(initMethod = "export", destroyMethod = "unexport")
 * @ConditionalOnProperty(name = "mse.rpc.mode", havingValue = "dubbo")
 * public ServiceConfig<RemoteAiTool> oaAiToolConfig(RemoteAiTool impl, ConfigurableListableBeanFactory bf) {
 *     ServiceConfig<RemoteAiTool> config = MseDubboSupport.serviceConfig(RemoteAiTool.class, impl, bf);
 *     config.setGroup("mse-oa");
 *     return config;
 * }
 * }</pre>
 * 单体聚合形态：无需导出——ai-service 直接收集容器内全部 RemoteAiTool Bean 注册为本地工具。
 *
 * <p>一个服务可提供多个工具，但 dubbo 形态只导出<b>一个</b>本接口实现（同 group 重复导出会
 * 被消费者随机命中）：多工具时实现两参 {@link #invoke(String, String)} 按 toolCode 内部分发
 * （mse-ai 调用侧固定携带 ai_tool 行的 tool_code；单工具实现无需理会，默认方法透传一参版本）。
 *
 * <p>invoke 入参/出参均为 JSON 字符串：参数 schema 由 {@link #describe()} 声明，
 * 模型按 schema 生成入参；跨服务调用不绑定强类型 DTO，避免契约耦合。
 */
public interface RemoteAiTool {

    /** 工具描述（toolCode 全局唯一，与 ai_tool 表登记的 tool_code 一致） */
    AiToolDescriptor describe();

    /**
     * 执行工具。
     *
     * @param argumentsJson 模型按 parametersJsonSchema 生成的入参（JSON 对象字符串）
     * @return 执行结果（success=false 时 content 为错误说明，模型可据此重试或告知用户）
     */
    AiToolResult invoke(String argumentsJson);

    /**
     * 执行工具（多工具提供方按 toolCode 分发；mse-ai 泛化调用固定走本方法）。
     * 默认实现忽略 toolCode 透传一参版本，单工具提供方零感知。
     *
     * @param toolCode      ai_tool 行登记的工具编码（mse-ai 侧传入，未识别时返回失败结果）
     * @param argumentsJson 模型按 parametersJsonSchema 生成的入参（JSON 对象字符串）
     * @return 执行结果（success=false 时 content 为错误说明，模型可据此重试或告知用户）
     */
    default AiToolResult invoke(String toolCode, String argumentsJson) {
        return invoke(argumentsJson);
    }
}
