package fun.commons.tokengateway.demo;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 控制层 demo 主类 (联调/冒烟用, 非生产组件; 端口 9400 对齐网关 gateway.backend.url 默认值).
 */
@SpringBootApplication
public class ControlPlaneDemoApplication {

    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(ControlPlaneDemoApplication.class, args);
    }
}
