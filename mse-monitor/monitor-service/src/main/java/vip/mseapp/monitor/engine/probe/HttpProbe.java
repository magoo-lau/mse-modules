package vip.mseapp.monitor.engine.probe;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.support.SimpleHttp;

import java.net.http.HttpResponse;
import java.util.Map;

/**
 * 通用 HTTP 探测（Nacos / Elasticsearch / 任意 URL）。
 *
 * <p>2xx 即 UP；响应为 JSON 且携带 status 字段时做语义识别——health "UP" 与 ES 集群
 * "green"/"yellow" 记入指标并视为 UP，"red"/"DOWN"/"OUT_OF_SERVICE" 视为 DOWN。
 */
@Component
public class HttpProbe implements Probe {

    private final JsonMapper jsonMapper;

    public HttpProbe(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean supports(String targetType) {
        return MonTarget.TYPE_HTTP.equals(targetType);
    }

    @Override
    public ProbeResult probe(MonTarget target, int timeoutMs) {
        String url = target.getServiceUrl();
        if (url == null || url.isBlank()) {
            return ProbeResult.down("探测地址未配置");
        }
        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response = SimpleHttp.get(url, timeoutMs);
            int latency = (int) (System.currentTimeMillis() - start);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ProbeResult.down("HTTP " + response.statusCode());
            }
            Map<String, Object> metrics = new java.util.LinkedHashMap<>();
            String body = response.body();
            if (body != null && body.strip().startsWith("{")) {
                try {
                    Map<?, ?> json = jsonMapper.readValue(body, Map.class);
                    Object status = json == null ? null : json.get("status");
                    if (status != null) {
                        String s = String.valueOf(status);
                        metrics.put("status", s);
                        if ("red".equalsIgnoreCase(s) || "DOWN".equalsIgnoreCase(s)
                                || "OUT_OF_SERVICE".equalsIgnoreCase(s)) {
                            return ProbeResult.down("status=" + s);
                        }
                    }
                } catch (Exception ignored) {
                    // JSON 解析失败按纯 2xx 处理
                }
            }
            return ProbeResult.up(latency, metrics);
        } catch (Exception e) {
            return ProbeResult.down(SimpleHttp.abbreviate(ServiceProbe.rootMessage(e)));
        }
    }
}
