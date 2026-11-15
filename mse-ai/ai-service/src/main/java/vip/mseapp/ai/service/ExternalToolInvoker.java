package vip.mseapp.ai.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import com.hubspot.jinjava.Jinjava;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vip.mseapp.ai.api.dto.AiToolResult;
import vip.mseapp.ai.entity.AiTool;
import vip.mseapp.ai.support.AiJson;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部工具调用器：执行 ai_tool 中 source=http（通用 HTTP 接口）/ dify（Dify 应用）的登记行，
 * 连接配置存 config_json。
 *
 * <p>url 为管理端登记的静态地址，不做模板渲染（模型入参只进入 query/body，杜绝参数注入
 * 改写目标地址；与管理端自配的 MCP endpoint 同信任级）。http 来源：GET/DELETE 参数拼 query，
 * POST/PUT/PATCH 参数作 JSON body 或经 bodyTemplate（Jinjava）渲染；dify 来源：blocking
 * 调用应用 API，按 appType 提取 data.outputs / data.answer。
 */
@Component
public class ExternalToolInvoker {

    private static final long DEFAULT_HTTP_TIMEOUT = 15_000L;
    private static final long DEFAULT_DIFY_TIMEOUT = 60_000L;
    private static final long MAX_TIMEOUT = 120_000L;
    private static final String DIFY_DEFAULT_BASE = "https://api.dify.ai";
    private static final String DIFY_DEFAULT_USER = "mse-ai";
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final Jinjava jinjava = new Jinjava();

    /** RestClient 按超时毫秒缓存（连接参数随超时走，配置可枚举） */
    private final Map<Long, RestClient> clients = new ConcurrentHashMap<>();

    public AiToolResult invoke(AiTool tool, String argumentsJson) {
        JsonNode config = AiJson.tryParse(tool.getConfigJson());
        if (config == null || !config.isObject() || config.size() == 0) {
            return AiToolResult.fail("外部工具连接配置缺失或非法（config_json）");
        }
        return "dify".equals(tool.getSource())
                ? invokeDify(config, argumentsJson)
                : invokeHttp(config, argumentsJson);
    }

    private AiToolResult invokeHttp(JsonNode config, String argumentsJson) {
        String url = config.path("url").asText("").trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return AiToolResult.fail("HTTP 工具配置非法：url 必填且以 http(s):// 开头");
        }
        String method = config.path("method").asText("POST").trim().toUpperCase();
        if (!ALLOWED_METHODS.contains(method)) {
            return AiToolResult.fail("HTTP 工具配置非法：method 仅支持 GET/POST/PUT/PATCH/DELETE");
        }
        Map<String, Object> args = parseArgs(argumentsJson);
        if (args == null) {
            return AiToolResult.fail("工具入参不是合法 JSON 对象");
        }
        if ("GET".equals(method) || "DELETE".equals(method)) {
            url = appendQuery(url, args);
        }

        RestClient.RequestBodySpec request = client(timeout(config, DEFAULT_HTTP_TIMEOUT))
                .method(HttpMethod.valueOf(method)).uri(url);
        JsonNode headers = config.path("headers");
        if (headers.isObject()) {
            headers.properties().forEach(e ->
                    request.header(e.getKey(), e.getValue().asText()));
        }
        String template = config.path("bodyTemplate").asText("");
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            String body;
            if (!template.isBlank()) {
                try {
                    body = jinjava.render(template, args);
                } catch (Exception e) {
                    return AiToolResult.fail("bodyTemplate 渲染失败: " + e.getMessage());
                }
            } else {
                body = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            }
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<String> entity = exchange(request);
        if (entity == null) {
            return AiToolResult.fail("HTTP 工具调用失败（连接异常或超时）");
        }
        int status = entity.getStatusCode().value();
        if (status < 200 || status >= 300) {
            return AiToolResult.fail("HTTP " + status + ": " + snippet(entity.getBody()));
        }
        return AiToolResult.ok(entity.getBody() == null ? "" : entity.getBody());
    }

    private AiToolResult invokeDify(JsonNode config, String argumentsJson) {
        String apiKey = config.path("apiKey").asText("").trim();
        if (apiKey.isBlank()) {
            return AiToolResult.fail("Dify 工具配置非法：apiKey 必填");
        }
        String base = config.path("baseUrl").asText(DIFY_DEFAULT_BASE).trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String appType = config.path("appType").asText("workflow").trim();
        String user = config.path("user").asText(DIFY_DEFAULT_USER).trim();

        Map<String, Object> args = parseArgs(argumentsJson);
        if (args == null) {
            return AiToolResult.fail("工具入参不是合法 JSON 对象");
        }
        String path;
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (appType) {
            case "workflow" -> {
                path = "/v1/workflows/run";
                payload.put("inputs", args);
            }
            case "completion" -> {
                path = "/v1/completion-messages";
                payload.put("inputs", args);
            }
            case "chat" -> {
                path = "/v1/chat-messages";
                Map<String, Object> inputs = new LinkedHashMap<>(args);
                Object query = inputs.remove("query");
                payload.put("query", query == null ? "" : String.valueOf(query));
                payload.put("inputs", inputs);
            }
            default -> {
                return AiToolResult.fail("Dify 工具配置非法：appType 仅支持 workflow/completion/chat");
            }
        }
        payload.put("response_mode", "blocking");
        payload.put("user", user);

        ResponseEntity<String> entity;
        try {
            entity = client(timeout(config, DEFAULT_DIFY_TIMEOUT)).post().uri(base + path)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(AiJson.toJson(payload))
                    .retrieve().onStatus(s -> false, (req, res) -> { }).toEntity(String.class);
        } catch (Exception e) {
            return AiToolResult.fail("Dify 调用失败: " + e.getMessage());
        }
        int status = entity.getStatusCode().value();
        if (status < 200 || status >= 300) {
            return AiToolResult.fail("Dify HTTP " + status + ": " + snippet(entity.getBody()));
        }
        JsonNode root = AiJson.tryParse(entity.getBody());
        if (root == null) {
            return AiToolResult.ok(entity.getBody());
        }
        if ("workflow".equals(appType)) {
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return AiToolResult.ok(entity.getBody());
            }
            String workflowStatus = data.path("status").asText("");
            if (!workflowStatus.isBlank() && !"succeeded".equals(workflowStatus)) {
                String error = data.path("error").asText("");
                return AiToolResult.fail("Dify 工作流状态 " + workflowStatus
                        + (error.isBlank() ? "" : ": " + error));
            }
            JsonNode outputs = data.path("outputs");
            if (outputs.isObject() && outputs.size() > 0) {
                return AiToolResult.ok(AiJson.toJson(outputs));
            }
            return AiToolResult.ok(data.toString());
        }
        JsonNode answer = root.path("data").path("answer");
        return AiToolResult.ok(answer.isMissingNode() || answer.isNull()
                ? entity.getBody() : answer.asText());
    }

    private ResponseEntity<String> exchange(RestClient.RequestBodySpec request) {
        try {
            // onStatus 恒 false：非 2xx 不抛异常，交由调用方按状态码判失败
            return request.retrieve().onStatus(s -> false, (req, res) -> { }).toEntity(String.class);
        } catch (Exception e) {
            return null;
        }
    }

    private RestClient client(long timeoutMillis) {
        int millis = (int) Math.min(Math.max(timeoutMillis, 1_000L), MAX_TIMEOUT);
        return clients.computeIfAbsent((long) millis, t -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(millis);
            factory.setReadTimeout(millis);
            return RestClient.builder().requestFactory(factory).build();
        });
    }

    private long timeout(JsonNode config, long defaultMillis) {
        long configured = config.path("timeoutMillis").asLong(defaultMillis);
        return configured <= 0 ? defaultMillis : configured;
    }

    private String appendQuery(String url, Map<String, Object> args) {
        if (args.isEmpty()) {
            return url;
        }
        StringBuilder query = new StringBuilder();
        args.forEach((key, value) -> {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
        });
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    /** 入参解析为 Map（非法 JSON 或非对象返回 null） */
    private Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        JsonNode node = AiJson.tryParse(argumentsJson);
        if (node == null || !node.isObject()) {
            return null;
        }
        try {
            return AiJson.MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    private String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "（空响应）";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "…";
    }
}
