package vip.mseapp.ai.workflow.exec;

import org.springframework.stereotype.Component;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.workflow.FlowProperties;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 请求节点：JDK HttpClient，url/headers/body 支持变量模板。
 *
 * <p>SSRF 防护：仅 http/https；默认拒绝解析到环回/内网/链路本地/任意地址的目标，
 * 命中 {@code mse.ai.flow.http.allowed-hosts} 白名单的域名放行。
 */
@Component
public class HttpRequestNodeExecutor implements NodeExecutor {

    private final FlowProperties properties;

    public HttpRequestNodeExecutor(FlowProperties properties) {
        this.properties = properties;
    }

    @Override
    public String type() {
        return "http";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        String url = VarRenderer.render(node.str("url", ""), ctx.resolver());
        if (url == null || url.isBlank()) {
            throw new ServiceException(400, "节点「" + node.title() + "」URL 为空");
        }
        URI uri = checkUrl(node, url);
        String method = node.str("method", "GET").toUpperCase();
        long timeout = node.lng("timeout", properties.getHttpTimeoutMillis());

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeout));
        boolean hasContentType = node.map("headers").keySet().stream()
                .anyMatch(name -> "content-type".equalsIgnoreCase(name));
        node.map("headers").forEach((name, value) -> builder.header(name,
                VarRenderer.render(String.valueOf(value), ctx.resolver())));
        String body = null;
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method)) {
            body = VarRenderer.render(node.str("body", ""), ctx.resolver());
            if (!hasContentType) {
                builder.header("Content-Type", "application/json");
            }
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        Map<String, Object> responseHeaders = new LinkedHashMap<>();
        String responseBody;
        int statusCode;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofMillis(timeout))
                    .build();
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            statusCode = response.statusCode();
            response.headers().map().forEach((name, values) -> responseHeaders.put(name, String.join(", ", values)));
            responseBody = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(502, "节点「" + node.title() + "」HTTP 请求失败: " + e.getMessage());
        }

        ctx.setOutput(node.id(), "status_code", statusCode);
        ctx.setOutput(node.id(), "headers", responseHeaders);
        ctx.setOutput(node.id(), "body", responseBody);
        Map<String, Object> json = AiJson.tryParse(responseBody) != null ? AiJson.toMap(responseBody) : null;
        if (json != null) {
            ctx.setOutput(node.id(), "json", json);
        }
    }

    private URI checkUrl(FlowNode node, String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new ServiceException(400, "节点「" + node.title() + "」URL 非法: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ServiceException(400, "节点「" + node.title() + "」仅支持 http/https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ServiceException(400, "节点「" + node.title() + "」URL 缺少主机名");
        }
        String normalized = host.toLowerCase();
        if (properties.getHttpAllowedHosts().stream().anyMatch(normalized::equals)) {
            return uri;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                    throw new ServiceException(403, "节点「" + node.title() + "」禁止访问内网地址: " + host
                            + "（如需放行请配置 mse.ai.flow.http.allowed-hosts）");
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(400, "节点「" + node.title() + "」主机解析失败: " + host);
        }
        return uri;
    }
}
