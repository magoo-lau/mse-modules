package vip.mseapp.boot.im;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * IM 微服务启动器：纯 {@code @SpringBootApplication}，业务积木经 SPI 自动装配
 * （vip.mseapp.im 包树与启动类零重叠，扫描默认值不触碰任何积木）。
 */
@SpringBootApplication
public class ImBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImBootApplication.class, args);
    }
}
