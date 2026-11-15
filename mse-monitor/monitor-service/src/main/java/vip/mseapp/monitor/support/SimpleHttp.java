package vip.mseapp.monitor.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 探测/通知共用的极简 HTTP 工具（JDK HttpClient，静态单例）。
 */
public final class SimpleHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private SimpleHttp() {
    }

    /** GET，返回响应；调用方自行判定状态码 */
    public static HttpResponse<String> get(String url, int timeoutMs) throws Exception {
        return get(url, timeoutMs, null);
    }

    /** GET，可附带请求头（如 actuator 令牌，见 {@link ActuatorAuth}）；返回响应，调用方自行判定状态码 */
    public static HttpResponse<String> get(String url, int timeoutMs, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET();
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** POST JSON，非 2xx 抛错（返回体截断附带） */
    public static String postJson(String url, String json, int timeoutMs) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response =
                CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        return response.body();
    }

    public static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
