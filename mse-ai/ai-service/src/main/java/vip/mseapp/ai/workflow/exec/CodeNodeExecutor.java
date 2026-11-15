package vip.mseapp.ai.workflow.exec;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.io.IOAccess;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.workflow.FlowProperties;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 代码执行节点：JavaScript（GraalJS 社区版沙箱）。
 *
 * <p>沙箱约束——无宿主访问、无 IO/进程/环境变量，代码须定义
 * {@code function main(args) { return {...} }}，返回对象经 JSON 序列化回传，
 * 各键作为节点输出变量。单节点限时（默认 10s）+ 强制关闭兜底。
 */
@Component
public class CodeNodeExecutor implements NodeExecutor {

    /** 沙箱执行线程池（daemon，不随工作流数量膨胀；经 ContextExecutorService 包装透传租户上下文） */
    private final ExecutorService sandboxPool = io.micrometer.context.ContextExecutorService.wrap(
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "mse-ai-flow-code");
                thread.setDaemon(true);
                return thread;
            }));

    private final FlowProperties properties;

    public CodeNodeExecutor(FlowProperties properties) {
        this.properties = properties;
    }

    @Override
    public String type() {
        return "code";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        String code = node.str("code", "");
        if (code.isBlank() || !code.contains("main")) {
            throw new ServiceException(400, "节点「" + node.title() + "」须定义 function main(args)");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        node.map("args").forEach((key, value) ->
                args.put(key, value instanceof String template ? VarRenderer.resolve(template, ctx.resolver()) : value));
        String argsJson = AiJson.toJson(args);
        int timeoutSeconds = node.intg("timeout", properties.getCodeTimeoutSeconds());

        String resultJson;
        // 显式持句柄：超时/取消时中断沙箱任务，避免线程继续空跑（GraalVM Context 关闭兜底在任务内）
        Future<String> future = sandboxPool.submit(() -> evalSandboxed(code, argsJson));
        try {
            resultJson = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new ServiceException(408, "节点「" + node.title() + "」代码执行超时（" + timeoutSeconds + "s）");
        } catch (Exception e) {
            future.cancel(true);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = String.valueOf(cause.getMessage());
            if (message.contains("No compatible language") || message.contains("engine")) {
                throw new ServiceException(400, "节点「" + node.title()
                        + "」JavaScript 引擎未部署（运行期需引入 org.graalvm.polyglot:js-community）");
            }
            throw new ServiceException(400, "节点「" + node.title() + "」代码执行失败: " + message);
        }
        Map<String, Object> outputs = AiJson.toMap(resultJson);
        ctx.setOutputs(node.id(), outputs);
    }

    /** 宿主隔离的求值：入参与出参都以 JSON 字符串经 guest 侧 JSON.parse/stringify 往返 */
    private String evalSandboxed(String code, String argsJson) {
        Context context = Context.newBuilder("js")
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowIO(IOAccess.NONE)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                .allowHostClassLookup(className -> false)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        try {
            context.eval("js", "var __args = " + argsJson + ";");
            context.eval("js", code);
            return context.eval("js", "JSON.stringify(main(__args)).toString()").asString();
        } finally {
            context.close(true);
        }
    }
}
