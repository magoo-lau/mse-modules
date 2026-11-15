package vip.mseapp.open.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 业务开放平台控制器自动装配（Spring Boot SPI）。
 *
 * <p>仅在 Servlet Web 环境注册；可用 {@code mse.service.open.controller.enabled=false}
 * 整体关闭默认控制器，由接入方自有控制器替代（基座同款开关语义）。
 * 机器凭证链路 /open/oauth/token、/open/api/** 为签名鉴权（无登录态注解），
 * 网关侧经 mse.gateway.ignore-urls 放行（见工程 README）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "mse.service.open.controller", name = "enabled", matchIfMissing = true)
@ComponentScan(basePackages = "vip.mseapp.open.controller")
public class OpenControllerAutoConfiguration {
}
