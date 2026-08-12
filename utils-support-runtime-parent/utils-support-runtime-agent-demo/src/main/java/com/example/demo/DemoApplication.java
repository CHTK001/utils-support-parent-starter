package com.example.demo;

import com.chua.runtime.apm.storage.StorageConfig;
import com.chua.runtime.apm.storage.StorageManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 演示应用入口 — Spring Boot Web 服务，被 RuntimeAgent 监控。
 *
 * <p>启动方式（运行时手动）：</p>
 * <pre>
 * java -Xbootclasspath/a:utils-support-runtime-agent-4.0.0.42.jar \
 *      -javaagent:utils-support-runtime-agent-4.0.0.42.jar \
 *      -jar utils-support-runtime-agent-demo-4.0.0.42.jar
 * </pre>
 *
 * <p>必须把 agent.jar 同时放上 {@code -Xbootclasspath/a:} 和 {@code -javaagent:}：
 * 前者让 agent jar 进入 bootstrap classloader（让 JVM 原生类 java.net.Socket、
 * java.util.logging.Logger 等可见），后者让 JVM 调用 Premain-Class。</p>
 *
 * <p>存储说明：agent 的 ApmBootstrap 在 bootstrap classloader 中初始化存储，
 * 该 classloader 无法访问 {@code java.sql} 与 sqlite-jdbc。因此本应用在自身
 * classloader（含 sqlite-jdbc）中重新初始化 {@link StorageManager}，使
 * {@code apm.storage.type=sqlite} 生效并落盘到本地 SQLite 文件。</p>
 *
 * <p>端口：8580，暴露端点：</p>
 * <ul>
 *   <li>GET  /order/create?amount=xxx — 创建订单</li>
 *   <li>GET  /agent/status — 查看 RuntimeAgent 状态</li>
 *   <li>GET  /api/apm/stats — 查看存储统计</li>
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
        initStorage();
        SpringApplication.run(DemoApplication.class, args);
    }

    /**
     * 在应用 classloader 中初始化存储，使 SQLite 持久化生效。
     *
     * <p>注意：agent 的 premain 会在 bootstrap classloader 中初始化存储，
     * 该 classloader 无法访问 {@code java.sql} 与 sqlite-jdbc，因此 premain
     * 阶段必须使用 inmemory（安全回退）。本方法在应用 classloader（含
     * sqlite-jdbc）中强制切换为 sqlite，实现本地落盘。</p>
     */
    private static void initStorage() {
        try {
            StorageConfig config = new StorageConfig();
            config.put("apm.storage.type", "sqlite");
            config.put("apm.storage.path", System.getProperty("apm.storage.path", "./apm.db"));
            config.put("apm.storage.retention.ms",
                    System.getProperty("apm.storage.retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000)));
            config.put("apm.storage.capacity", System.getProperty("apm.storage.capacity", "100000"));
            StorageManager.init(config);
        } catch (Exception e) {
            System.err.println("[DemoApplication] 存储初始化失败: " + e.getMessage());
        }
    }
}
