package vip.mseapp.open.dispatch;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.AntPathMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.Result;
import vip.mseapp.open.entity.OpenApiDef;
import vip.mseapp.open.security.OpenApiAuthInterceptor;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放 API 泛化分发控制器（/open/api/** 兜底宽映射）。
 *
 * <p>与内置端点共存：Spring MVC 精确映射优先，内置能力（user/dept/role/bind 的精确
 * Controller）照常承接；其余命中目录的请求（业务积木自注册条目）落到本类，按目录
 * {@code target} 经 {@link OpenRpcDispatcher} 泛化分发到业务契约。
 *
 * <p>鉴权已由 {@code OpenApiAuthInterceptor} 完成（token → 授权 → 限流 → 租户注入），
 * 本类只做参数组装与转发：query + body（JSON 对象）+ 路径模板变量合并为请求 JSON
 * （优先级从低到高：query &lt; body &lt; 路径变量）。
 */
@RestController
public class OpenDispatchController {

    private final OpenRpcDispatcher dispatcher;
    private final JsonMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public OpenDispatchController(OpenRpcDispatcher dispatcher, JsonMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(value = "/open/api/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public Result<JsonNode> dispatch(HttpServletRequest request,
                                     @RequestBody(required = false) Map<String, Object> body) {
        OpenApiDef api = (OpenApiDef) request.getAttribute(OpenApiAuthInterceptor.ATTR_API_DEF);
        if (api == null) {
            throw new ServiceException(404, "API 未开放: " + request.getMethod() + " " + request.getRequestURI());
        }
        if (api.getTarget() == null || api.getTarget().isBlank()) {
            throw new ServiceException(404, "API 未配置分发目标: " + api.getCode());
        }
        Map<String, Object> args = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) ->
                args.put(name, values != null && values.length == 1 ? values[0] : Arrays.asList(values)));
        if (body != null && !body.isEmpty()) {
            args.putAll(body);
        }
        Map<String, String> pathVariables =
                pathMatcher.extractUriTemplateVariables(api.getPath(), request.getRequestURI());
        if (!pathVariables.isEmpty()) {
            args.putAll(pathVariables);
        }

        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            throw new ServiceException(400, "请求参数序列化失败");
        }
        Object result = dispatcher.invoke(api.getTarget(), requestJson);
        return Result.ok(asTree(result));
    }

    /** 契约约定返回 JSON 字符串；解析失败按原始字符串透传（兜底非 JSON 返回） */
    private JsonNode asTree(Object result) {
        if (result instanceof String text) {
            try {
                return objectMapper.readTree(text);
            } catch (Exception ignored) {
                return objectMapper.valueToTree(text);
            }
        }
        return result == null ? null : objectMapper.valueToTree(result);
    }
}
