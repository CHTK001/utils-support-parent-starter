package com.chua.debezium.support.directory;

import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.datasource.support.config.debezium.DebeziumConnectorConfig;
import com.chua.datasource.support.config.debezium.DebeziumEnvironmentSetup;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import lombok.extern.slf4j.Slf4j;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Debezium CDC 目录轮询实现，基于 Debezium Engine。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DebeziumPolledDirectory implements PolledDirectory {

    /**
     * 逻辑监听路径（数据库名或表名）
     */
    private final String listenPath;

    /**
     * 目录轮询环境
     */
    private final DirectoryPollerEnvironment environment;

    /**
     * 已注册的监听器列表
     */
    private final List<PolledListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Debezium Engine 实例
     */
    private DebeziumEngine<ChangeEvent<String, String>> engine;

    /**
     * Debezium 执行线程池
     */
    private ExecutorService executor;

    /**
     * 构造 Debezium CDC 轮询器。
     *
     * @param listenPath  逻辑路径（数据库名或表名）
     * @param environment 环境配置
     */
    public DebeziumPolledDirectory(String listenPath, DirectoryPollerEnvironment environment) {
        this.listenPath = listenPath;
        this.environment = environment;
    }

    @Override
    /** 添加监听器 */
    public void addListener(PolledListener listener) {
        listeners.add(listener);
    }

    @Override
    /** 开始 */
    public void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor pollerExecutor) {
 // 事件-driven, 执行器 传 空 即可

        // 环境自动检测与设置
        String connectorType = environment.getProperty("debezium.connector.type");
        boolean autoSetup = Boolean.parseBoolean(environment.getProperty("debezium.auto.setup", "false"));
        if (autoSetup && connectorType != null) {
            DebeziumEnvironmentSetup setup = ServiceProvider.of(DebeziumEnvironmentSetup.class)
                    .getExtension(connectorType);
            if (setup != null) {
                try {
                    log.info("[debezium-cdc] 环境检测: type={}", connectorType);
                    if (!setup.isReady(environment)) {
                        log.warn("[debezium-cdc] 环境未就绪，尝试自动配置: type={}", connectorType);
                        setup.setup(environment);
                        if (!setup.isReady(environment)) {
                            log.warn("[debezium-cdc] 环境自动配置后仍未就绪，部分功能可能不可用: type={}", connectorType);
                        } else {
                            log.info("[debezium-cdc] 环境自动配置成功: type={}", connectorType);
                        }
                    } else {
                        log.info("[debezium-cdc] 环境已就绪: type={}", connectorType);
                    }
                } catch (Exception e) {
                    log.error("[debezium-cdc] 环境设置失败: type={}, msg={}", connectorType, e.getMessage(), e);
                }
            }
        }

        Properties props = new Properties();
        props.setProperty("name", "debezium-" + listenPath);
        props.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        props.setProperty("offset.storage.file.filename",
                environment.getProperty("offset.storage.file.filename", "debezium-offset.dat"));
        props.setProperty("offset.flush.interval.ms", "1000");
        props.setProperty("database.server.name", listenPath);
        props.setProperty("topic.prefix", listenPath);

        // 通过 SPI 查找连接器配置，自动映射环境属性
        String connectorClass = environment.getProperty("connector.class");

        if (connectorClass == null && connectorType != null) {
            DebeziumConnectorConfig config = ServiceProvider.of(DebeziumConnectorConfig.class)
                    .getExtension(connectorType);
            if (config != null) {
                connectorClass = config.connectorClass();
                config.configure(props, environment);
                log.info("[debezium-cdc] 连接器 SPI 自动配置: type={}, class={}", connectorType, connectorClass);
            }
        }

        if (connectorClass == null) {
            connectorClass = "io.debezium.connector.mysql.MySqlConnector";
            props.setProperty("database.hostname", environment.getString("db.host", "localhost"));
            props.setProperty("database.port", environment.getString("db.port", "3306"));
            props.setProperty("database.user", environment.getString("db.username", "root"));
            props.setProperty("database.password", environment.getString("db.password", ""));
            props.setProperty("database.include.list", environment.getString("db.name", listenPath));
        }

        props.setProperty("connector.class", connectorClass);

 // 透传额外的 database./snapshot./tombstones./键. 开头的属性
        for (var entry : environment.getProperties().entrySet()) {
            String k = entry.getKey();
            if (k.startsWith("database.") || k.startsWith("snapshot.")
                    || k.startsWith("tombstones.") || k.startsWith("key.")
                    || k.startsWith("offset.") || k.startsWith("heartbeat.")) {
                props.setProperty(k, entry.getValue());
            }
        }

        engine = DebeziumEngine.create(Json.class)
                .using(props)
                .notifying(record -> {
                    String value = record.value();
                    if (value == null) {
                        return;
                    }
                    dispatch(value);
                })
                .build();

        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder().setNameFormat("debezium-" + listenPath + "-%d").setDaemon(true).build());
        executor.execute(engine);

        log.info("[debezium-cdc] CDC 已启动: listenPath={}, connectorClass={}", listenPath, connectorClass);
    }

    /**
     * 分发
     *
     * @param value 值
     */
    private void dispatch(String value) {
        String op = extractOp(value);
        String table = extractTable(value);
        if (table == null) {
            table = listenPath;
        }

        EventObserver observer = EventObserver.builder()
                .currentPath(listenPath)
                .triggerFile(table)
                .source(value)
                .build();

        for (PolledListener l : listeners) {
            try {
                switch (op) {
                    case "c" -> l.onCreate(WatcherEvent.CREATE, observer);
                    case "u" -> l.onModify(WatcherEvent.MODIFY, observer);
                    case "d" -> l.onDelete(WatcherEvent.DELETE, observer);
                    default -> log.warn("[debezium-cdc] 未知 CDC 操作类型: {}", op);
                }
            } catch (Exception e) {
                log.error("[debezium-cdc] 监听器处理异常", e);
            }
        }
    }

    /**
     * extracttable
     *
     * @param value 值
     * @return extractTable的结果
     */
    private String extractTable(String value) {
        int idx = value.indexOf("\"table\":\"");
        if (idx < 0) {
            return null;
        }
        int start = idx + 9;
        int end = value.indexOf("\"", start);
        return end > start ? value.substring(start, end) : null;
    }

    /**
     * extractop
     *
     * @param value 值
     * @return extractOp的结果
     */
    private String extractOp(String value) {
        int idx = value.indexOf("\"op\":\"");
        if (idx < 0) {
            return "";
        }
        return idx + 6 < value.length() ? value.substring(idx + 6, idx + 7) : "";
    }

    @Override
    /** Upgrade */
    public void upgrade() {
        // 由事件驱动，无需轮询
    }

    @Override
    /** 是否delegatedoperating系统 */
    public boolean isDelegatedOperatingSystem() {
        return true;
    }

    @Override
    /** 关闭 */
    public void close() {
        if (engine != null) {
            try {
                engine.close();
            } catch (IOException ignored) {
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        listeners.clear();
    }
}
