package vip.mseapp.boot.open;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务中台/开放平台启动器。
 *
 * <p>启动类位于 vip.mseapp.boot.open，与业务积木包树零重叠——默认扫描不触碰任何积木：
 * 自有积木（open-service）引入 jar 即经 Spring Boot SPI 自动装配（Controller/Service/Mapper
 * 全部就绪）；基座能力不打包在本服务——鉴权数据源与基座用户/部门/角色能力经契约
 * （starter-bridge + api-*）消费 mse-system/mse-tenant，登录与系统管理收敛在基座微服务。
 *
 * <p>开放 API（/open/api/**）由 OpenApiAuthInterceptor 以应用 access_token 鉴权，
 * 与基座 Sa-Token 人机会话（/openapp/** 管理端、/auth 登录）互不干扰。
 */
@SpringBootApplication
public class OpenBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenBootApplication.class, args);
    }
}
