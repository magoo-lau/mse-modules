package vip.mseapp.boot.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI 平台启动器（微服务形态）。
 *
 * <p>启动类位于 vip.mseapp.boot.ai，与业务积木包树零重叠——默认扫描不触碰任何积木，
 * 一切组件经 Spring Boot SPI 自动装配：自有积木（ai-api/ai-service）引入 jar 即得
 * 全部能力（含 Mapper 注册）；基座能力不打包在本服务——鉴权数据源/独立库租户路由经
 * starter-bridge 以契约消费 mse-system/mse-tenant。
 *
 * <p>微服务形态（mse.rpc.mode=dubbo）：本地仅 application.yaml（spring.config.import
 * 接入 Nacos），数据源/Redis/模型密钥等统一在配置中心管理（服务专属 Data ID 留档
 * docker/nacos/config/mse-ai.yaml），启动即注册供网关 lb://mse-ai 路由。
 */
@SpringBootApplication
public class AiBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiBootApplication.class, args);
    }
}
