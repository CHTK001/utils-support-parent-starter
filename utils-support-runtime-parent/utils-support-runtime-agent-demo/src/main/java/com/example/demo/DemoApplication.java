package com.example.demo;

import com.chua.runtime.apm.storage.StorageConfig;
import com.chua.runtime.apm.storage.StorageManager;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@SpringBootApplication
public class DemoApplication {

    /**
     * 存储类型：未初始化（agent 尚未注入存储）
     */
    private static final String STORAGE_TYPE_NOOP = "noop";

    /**
     * 存储类型：SQLite 持久化
     */
    private static final String STORAGE_TYPE_SQLITE = "sqlite";

    /**
     * 配置键：存储类型
     */
    private static final String KEY_STORAGE_TYPE = "apm.storage.type";

    /**
     * 配置键：数据库文件路径
     */
    private static final String KEY_STORAGE_PATH = "apm.storage.path";

    /**
     * 配置键：保留时长（毫秒）
     */
    private static final String KEY_STORAGE_RETENTION_MS = "apm.storage.retention.ms";

    /**
     * 配置键：单表最大行数
     */
    private static final String KEY_STORAGE_CAPACITY = "apm.storage.capacity";

    /**
     * 默认数据库文件路径
     */
    private static final String DEFAULT_DB_PATH = "./apm.db";

    /**
     * 默认保留时长（7 天，毫秒）
     */
    private static final long DEFAULT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * 默认单表最大行数
     */
    private static final String DEFAULT_CAPACITY = "100000";

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
     * 阶段必须使用 inmemory（安全回退）。若 agent 已初始化（inmemory 且
     * 独立于本应用的初始化），则保留其存储用于实时采集；否则本方法在
     * 应用 classloader（含 sqlite-jdbc）中切换到 sqlite，实现本地落盘。</p>
     */
    private static void initStorage() {
        try {
            String current = StorageManager.get().name();
            if (!STORAGE_TYPE_NOOP.equals(current)) {
                log.info("存储已初始化: {}，跳过 SQLite 切换（agent 采集保留）", current);
                return;
            }
            StorageConfig config = new StorageConfig();
            config.put(KEY_STORAGE_TYPE, STORAGE_TYPE_SQLITE);
            config.put(KEY_STORAGE_PATH, System.getProperty(KEY_STORAGE_PATH, DEFAULT_DB_PATH));
            config.put(KEY_STORAGE_RETENTION_MS,
                    System.getProperty(KEY_STORAGE_RETENTION_MS, String.valueOf(DEFAULT_RETENTION_MS)));
            config.put(KEY_STORAGE_CAPACITY, System.getProperty(KEY_STORAGE_CAPACITY, DEFAULT_CAPACITY));
            StorageManager.init(config);
            log.info("SQLite 存储已初始化: {}", StorageManager.get().name());
        } catch (Throwable t) {
            log.error("存储初始化失败: {}", t.getMessage());
        }
    }
}
