package vip.mseapp.boot.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 搜索引擎服务启动器（微服务形态）。
 *
 * <p>启动类位于 vip.mseapp.boot.search，与业务积木包树零重叠——默认扫描不触碰任何积木，
 * 一切组件经 Spring Boot SPI 自动装配：自有积木（search-api/search-service）引入 jar 即得
 * 全部能力（含 Mapper 注册）；基座能力不打包在本服务——鉴权数据源经 starter-bridge 以契约
 * 消费 mse-system，个人数据过滤的数据范围经 api-system 契约（RemoteDataScopeService）解析。
 *
 * <p>微服务形态（mse.rpc.mode=dubbo）：本地仅 application.yaml（spring.config.import
 * 接入 Nacos），数据源/Redis/Elasticsearch/闲时窗口等统一在配置中心管理（服务专属 Data ID
 * 留档 docker/nacos/config/mse-search.yaml），启动即注册供网关 lb://mse-search 路由。
 */
@SpringBootApplication
public class SearchBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchBootApplication.class, args);
    }
}
