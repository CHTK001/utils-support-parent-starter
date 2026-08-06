package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 演示应用入口 — Spring Boot Web 服务，被 RuntimeAgent 监控。
 *
 * <p>启动方式（运行时手动）：</p>
 * <pre>
 * java -javaagent:utils-support-runtime-agent-4.0.0.42.jar \
 *      -jar utils-support-runtime-agent-demo-4.0.0.42.jar
 * </pre>
 *
 * <p>端口：8080，暴露端点：</p>
 * <ul>
 *   <li>GET  /order/create?amount=xxx — 创建订单</li>
 *   <li>GET  /agent/status — 查看 RuntimeAgent 状态</li>
 *   <li>GET  /health — Spring Boot Actuator</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpringBootApplication
public class DemoApplication {

    /**
     * 启动入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
