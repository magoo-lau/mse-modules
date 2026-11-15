package vip.mseapp.monitor.engine.probe;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.monitor.config.MonitorProperties;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.support.ActuatorAuth;
import vip.mseapp.monitor.support.SimpleHttp;

import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Java 微服务探测：GET {serviceUrl}/actuator/health，解析 health 聚合状态（zero 侵入，各服务零改动）。
 *
 * <p>目标服务开启 actuator 鉴权（mse.actuator.auth.token）时经 mse.monitor.actuator-token
 * 携带同名令牌头（同一环境变量占位，两侧天然一致）。
 */
@Component
public class ServiceProbe implements Probe {

    private final JsonMapper jsonMapper;
    private final MonitorProperties properties;

    public ServiceProbe(JsonMapper jsonMapper, MonitorProperties properties) {
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    @Override
    public boolean supports(String targetType) {
        return MonTarget.TYPE_SERVICE.equals(targetType);
    }

    @Override
    public ProbeResult probe(MonTarget target, int timeoutMs) {
        String base = target.getServiceUrl();
        if (base == null || base.isBlank()) {
            return ProbeResult.down("服务地址未配置");
        }
        String url = base.endsWith("/") ? base + "actuator/health" : base + "/actuator/health";
        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response =
                    SimpleHttp.get(url, timeoutMs, ActuatorAuth.headers(properties.getActuatorToken()));
            int latency = (int) (System.currentTimeMillis() - start);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ProbeResult.down("HTTP " + response.statusCode());
            }
            String health = "UP";
            try {
                Map<?, ?> body = jsonMapper.readValue(response.body(), Map.class);
                Object status = body == null ? null : body.get("status");
                if (status != null) {
                    health = String.valueOf(status);
                }
            } catch (Exception ignored) {
                // 非 JSON 响应按 2xx=UP 处理
            }
            return ProbeResult.up(latency, Map.of("health", health));
        } catch (Exception e) {
            return ProbeResult.down(SimpleHttp.abbreviate(rootMessage(e)));
        }
    }

    static String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
