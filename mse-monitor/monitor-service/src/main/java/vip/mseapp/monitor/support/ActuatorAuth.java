package vip.mseapp.monitor.support;

import java.util.Map;

/**
 * 目标服务 actuator 端点鉴权头（服务侧 mse.actuator.auth.token 的框架默认请求头）。
 *
 * <p>令牌经 MonitorProperties.actuatorToken 配置（${MSE_ACTUATOR_TOKEN:} 占位与服务侧同源）；
 * 空 = 服务未开启鉴权，返回空 Map 匿名拉取。若服务侧自定义了请求头名，此处需同步修改。
 */
public final class ActuatorAuth {

    /** 与框架 ActuatorAuthProperties 默认请求头一致 */
    public static final String HEADER = "X-Actuator-Token";

    private ActuatorAuth() {
    }

    /** 令牌非空返回携带令牌的单头 Map，否则空 Map（SimpleHttp.get 对 null/空均不设头） */
    public static Map<String, String> headers(String token) {
        return token == null || token.isBlank() ? Map.of() : Map.of(HEADER, token);
    }
}
