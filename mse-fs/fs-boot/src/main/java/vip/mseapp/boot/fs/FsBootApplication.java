package vip.mseapp.boot.fs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 文件存储服务启动器。
 *
 * <p>启动类位于 vip.mseapp.boot.fs，与业务积木包树零重叠——默认扫描不触碰任何积木：
 * 自有积木（fs-service）引入 jar 即经 Spring Boot SPI 自动装配（Controller/Service/Mapper/
 * 存储实现全部就绪）；基座能力不打包在本服务——鉴权数据源（@SaCheckPermission）/独立库
 * 租户路由经 starter-bridge 以契约消费 mse-system/mse-tenant，登录与系统管理收敛在基座微服务。
 */
@SpringBootApplication
public class FsBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(FsBootApplication.class, args);
    }
}
