package vip.mseapp.ai.tool;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.ai.api.RemoteAiTool;
import vip.mseapp.ai.api.dto.AiToolDescriptor;
import vip.mseapp.ai.api.dto.AiToolResult;
import vip.mseapp.ai.entity.AiBizModule;
import vip.mseapp.ai.service.AiBizModuleService;
import vip.mseapp.ai.service.RpcToolInvoker;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用业务查询路由门面（biz_query）：跨模块业务数据问答的第一入口。本工具**不做任何
 * 业务库连接与 SQL 执行**——按 {@link AiBizModuleService} 模块注册表把查询转发到归属
 * 模块自提供的查询工具（AbstractBizQueryTool 子类，如 oa_biz_query），在模块自身进程内
 * 用其自有租户路由连库执行（数据不出归属服务）。
 *
 * <p>action=list_modules 返回模块目录（编码/名称/关键词），模型据此判定问题归属并携带
 * module 参数调用 meta_search/table/execute；单体模式转发到容器内同编码 Bean 直调，
 * 微服务（dubbo）模式经 RpcToolInvoker 泛化调用（group=归属服务名，租户上下文随
 * MseDubboContextFilter 透传）。模块未注册/停用时拒绝并列出可用模块。
 */
@Component
public class BizQueryTool implements RemoteAiTool {

    private static final String TOOL_CODE = "biz_query";

    private final JsonMapper objectMapper;
    private final AiBizModuleService moduleService;
    private final RpcToolInvoker rpcToolInvoker;
    private final ObjectProvider<RemoteAiTool> localTools;
    private final Environment environment;

    public BizQueryTool(JsonMapper objectMapper,
                        AiBizModuleService moduleService,
                        RpcToolInvoker rpcToolInvoker,
                        ObjectProvider<RemoteAiTool> localTools,
                        Environment environment) {
        this.objectMapper = objectMapper;
        this.moduleService = moduleService;
        this.rpcToolInvoker = rpcToolInvoker;
        this.localTools = localTools;
        this.environment = environment;
    }

    @Override
    public AiToolDescriptor describe() {
        AiToolDescriptor descriptor = new AiToolDescriptor();
        descriptor.setToolCode(TOOL_CODE);
        descriptor.setName("业务库智能查询");
        descriptor.setDescription("跨模块查询业务库生产业务表（协同办公/制造执行/微信公众号等业务模块）。"
                + "第一步 action=list_modules 获取可用业务模块目录（编码/名称/关键词），"
                + "根据用户问题选择最匹配的 module；后续 meta_search/table/execute 都必须携带 module 参数（模块编码）。"
                + "查询由归属模块服务执行：自动按租户过滤（跨租户隔离）、自动剔除已删除数据（deleted=0）、自动分页限行；"
                + "系统表与凭证表（sys_/ai_ 等）不可查询。写 SQL 前先用 meta_search 确认表和列名。"
                + "meta_search 返回空即该模块目录中无匹配业务表——这是最终结论，请勿更换关键字重试，直接如实告知用户。");
        descriptor.setParametersJsonSchema("""
                {"type":"object","properties":{
                  "action":{"type":"string","enum":["list_modules","meta_search","table","execute"],
                    "description":"list_modules=获取业务模块目录（路由第一步）；meta_search=检索模块业务表/列元数据；table=指定表名查列清单；execute=执行受控只读 SQL"},
                  "module":{"type":"string",
                    "description":"业务模块编码（list_modules 返回的 module 值，如 oa）；meta_search/table/execute 必填"},
                  "keyword":{"type":"array","items":{"type":"string"},
                    "description":"meta_search 的表名/注释关键字列表（一次传多个，工具内部合并去重返回）；返回空即无匹配业务表，属最终结论，勿再更换关键字重试"},
                  "table":{"type":"string","description":"table 动作指定表名，返回该表的列清单（写 SQL 前先确认列名）"},
                  "sql":{"type":"string","description":"execute 的只读 SELECT 语句（不含分页，系统自动追加）"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最大受系统行数上限约束"}
                },"required":["action"]}""");
        return descriptor;
    }

    @Override
    public AiToolResult invoke(String argumentsJson) {
        try {
            Map<String, Object> args = argumentsJson == null || argumentsJson.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
                    });
            String action = String.valueOf(args.getOrDefault("action", ""));
            return switch (action) {
                case "list_modules" -> listModules();
                case "meta_search", "table", "execute" -> forward(action, args);
                default -> AiToolResult.fail("未知的 action：" + action);
            };
        } catch (ServiceException e) {
            return AiToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("工具执行失败：" + e.getMessage());
        }
    }

    /** 模块目录：模型据此判定用户问题归属哪个业务模块 */
    private AiToolResult listModules() {
        List<Map<String, Object>> modules = new ArrayList<>();
        for (AiBizModule module : moduleService.listEnabled()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("module", module.getModuleCode());
            row.put("name", module.getName());
            row.put("description", module.getDescription());
            row.put("keywords", module.getKeywords());
            modules.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", modules.size());
        result.put("modules", modules);
        if (modules.isEmpty()) {
            result.put("note", "暂无已注册业务模块：请直接如实告知用户当前没有可查询的业务模块"
                    + "（业务积木未部署或未声明）。");
        }
        return AiToolResult.ok(toJson(result));
    }

    /** 按 module 查注册表转发到归属模块的查询工具（参数剥离 module 原样透传，结果原样返回） */
    private AiToolResult forward(String action, Map<String, Object> args) {
        String moduleCode = args.get("module") == null ? "" : String.valueOf(args.get("module")).trim();
        if (moduleCode.isEmpty()) {
            return AiToolResult.fail("缺少 module 参数（业务模块编码）。请先调用 action=list_modules "
                    + "获取模块目录后携带 module 重试");
        }
        AiBizModule module = moduleService.getEnabled(moduleCode);
        Map<String, Object> forwarded = new LinkedHashMap<>(args);
        forwarded.put("action", action);
        forwarded.remove("module");
        String argumentsJson = toJson(forwarded);
        if (isDubboMode()) {
            return rpcToolInvoker.invoke(module.getServiceName(), module.getToolCode(), argumentsJson);
        }
        RemoteAiTool local = findLocalTool(module.getToolCode());
        if (local == null) {
            return AiToolResult.fail("模块查询工具未就绪：" + module.getToolCode()
                    + "（模块 " + module.getName() + "，归属服务 " + module.getServiceName()
                    + "）。确认对应业务积木已部署/聚合后重试");
        }
        return local.invoke(module.getToolCode(), argumentsJson);
    }

    private boolean isDubboMode() {
        return "dubbo".equalsIgnoreCase(environment.getProperty("mse.rpc.mode", "local"));
    }

    /** 单体模式按工具编码取容器内本地 Bean（模块查询工具与 mse-ai 同进程） */
    private RemoteAiTool findLocalTool(String toolCode) {
        for (RemoteAiTool tool : localTools) {
            try {
                if (tool.describe() != null && toolCode.equals(tool.describe().getToolCode())) {
                    return tool;
                }
            } catch (Exception ignored) {
                // 描述异常的 Bean 不参与匹配
            }
        }
        return null;
    }

    private String toJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
