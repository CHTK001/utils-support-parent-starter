package com.chua.filesystem.log.support.polling;

import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import com.chua.common.support.lang.directory.executor.VirtualThreadPollerExecutor;
import com.chua.filesystem.log.support.SystemLogService;
import com.chua.filesystem.log.support.model.LogEntry;
import com.chua.filesystem.log.support.model.LogLevel;
import com.chua.filesystem.log.support.model.LogQuery;
import com.chua.filesystem.log.support.bridge.PlatformSystems;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统日志轮询监听器 - 实时监听系统日志变更
 * <p>
 * 实现 {@link PolledDirectory} 接口，通过定时调用 {@link SystemLogService} 获取最新系统日志，
 * 与上次轮询快照对比后向监听器分发新增日志事件（{@link WatcherEvent#MODIFY}）。
 * 适用于跨平台实时日志流监控场景，替代 {@code tail -f} 效果。
 * </p>
 * <p>
 * 内部以 {@link LogEntry#timestamp} 作为游标，仅推送上次轮询之后新出现的日志条目。
 * 支持按日志级别过滤，默认轮询间隔 5 秒，可按需调整。
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 监听所有级别、全部日志源
 * SyslogPolledDirectory watcher = SyslogPolledDirectory.builder().build();
 *
 * watcher.addListener(new PolledListener() {
 *     @Override
 *     public void onModify(WatcherEvent event, EventObserver observer) {
 *         System.out.println("新日志: " + observer.getSource());
 *     }
 * });
 *
 * watcher.start(DirectoryPollerEnvironment.defaults());
 *
 * // 过滤错误级别，轮询间隔 2 秒
 * SyslogPolledDirectory errorWatcher = SyslogPolledDirectory.builder()
 *     .minLevel(LogLevel.ERROR)
 *     .pollIntervalSeconds(2)
 *     .build();
 *
 * errorWatcher.start(DirectoryPollerEnvironment.defaults());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SyslogPolledDirectory implements PolledDirectory {

    /**
     * 日志来源，null 表示全部来源
     */
    private final String source;

    /**
     * 消息匹配模式，null 表示不过滤
     */
    private final String pattern;

    /**
     * 最低日志级别，null 表示所有级别
     */
    private final LogLevel minLevel;

    /**
     * 轮询间隔（秒）
     */
    private final int pollIntervalSeconds;

    /**
     * 上次轮询最后一条日志的时间戳游标，用于增量检测新日志
     */
    private volatile String lastTimestamp;

    /**
     * 事件监听器列表
     */
    private final List<PolledListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 运行状态
     */
    private volatile boolean running = false;

    /**
     * 关闭标志，close() 后不可再 upgrade
     */
    private volatile boolean closed = false;

    /**
     * 系统日志服务实例
     */
    private final SystemLogService logService;

    /**
     * 构造系统日志轮询监听器
     *
     * @param source              日志来源（如 "System"/"Application"/"Security"），null 表示全部
     * @param pattern             消息匹配 glob 模式，null 表示不过滤
     * @param minLevel            最低日志级别，null 表示所有级别
     * @param pollIntervalSeconds 轮询间隔（秒）
     */
    private SyslogPolledDirectory(String source, String pattern, LogLevel minLevel, int pollIntervalSeconds) {
        this(source, pattern, minLevel, pollIntervalSeconds, SystemLogService.getInstance());
    }

    /**
     * 构造系统日志轮询监听器（注入日志服务，便于测试）。
     *
     * @param source              日志来源
     * @param pattern             消息匹配模式
     * @param minLevel            最低日志级别
     * @param pollIntervalSeconds 轮询间隔（秒）
     * @param logService          系统日志服务实例
     */
    SyslogPolledDirectory(String source, String pattern, LogLevel minLevel, int pollIntervalSeconds, SystemLogService logService) {
        this.source = source;
        this.pattern = pattern;
        this.minLevel = minLevel;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.logService = logService;
    }

    /**
     * 判断是否委托操作系统监听。返回 false，本实现使用定时轮询。
     */
    @Override
    public boolean isDelegatedOperatingSystem() {
        return false;
    }

    /**
     * 注册事件监听器
     */
    @Override
    public void addListener(PolledListener listener) {
        listeners.add(listener);
    }

    /**
     * 启动系统日志轮询监听
     */
    @Override
    public void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor executor) {
        closed = false;
        running = true;
        log.info("系统日志轮询启动: source={}, pattern={}, minLevel={}, interval={}s, os={}",
                source, pattern, minLevel, pollIntervalSeconds, PlatformSystems.getOsName());

        if (executor != null) {
            executor.start();
        } else {
            startPollingThread(environment);
        }
    }

    /**
     * 启动轮询线程（简化入口）
     */
    @Override
    public void start(DirectoryPollerEnvironment environment) {
        start(environment, null);
    }

    /**
     * 执行一次轮询，拉取系统日志，与上次快照对比，向监听器分发新增条目。
     * <p>
     * 每次轮询按升序（ORDER_ASC）查询系统日志，确保游标在时间轴上单调前进。
     * 以 {@link LogEntry#timestamp} 字段作为增量标记。
     * </p>
     */
    @Override
    public void upgrade() {
        if (closed || !logService.isAvailable()) {
            return;
        }

        try {
            // 每次最多拉取 pollIntervalSeconds * 10 条，避免长轮询时 provider 压力过大
            int fetchLimit = Math.max(pollIntervalSeconds * 10, 50);
            LogQuery query = LogQuery.builder()
                    .source(source)
                    .pattern(pattern != null ? pattern : "*")
                    .minLevel(minLevel)
                    .maxResults(fetchLimit)
                    .order(LogQuery.ORDER_ASC)
                    .build();

            List<LogEntry> entries = logService.search(query);
            if (entries == null || entries.isEmpty()) {
                return;
            }

            String cursor = lastTimestamp;
            boolean advanced = false;

            for (LogEntry entry : entries) {
                // 首次轮询（无游标）：仅记录游标，不推送历史数据
                if (cursor == null) {
                    lastTimestamp = entry.timestamp();
                    advanced = true;
                    continue;
                }

                // 仅推送游标之后的新条目
                if (entry.timestamp().compareTo(cursor) > 0) {
                    lastTimestamp = entry.timestamp();
                    advanced = true;

                    fireLogEntry(entry);
                }
            }

            if (advanced && log.isDebugEnabled()) {
                log.debug("轮询完成，共处理 {} 条日志，游标推进至: {}", entries.size(), lastTimestamp);
            }
        } catch (Exception e) {
            log.error("系统日志轮询异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 向所有监听器分发新日志条目
     */
    private void fireLogEntry(LogEntry entry) {
        EventObserver observer = EventObserver.builder()
                .currentPath(source != null ? source : "all-sources")
                .triggerFile(entry.source() + "-" + entry.level())
                .source(entry)
                .eventType(WatcherEvent.MODIFY)
                .build();

        for (PolledListener listener : listeners) {
            try {
                listener.onModify(WatcherEvent.MODIFY, observer);
            } catch (Exception e) {
                log.error("日志监听器分发异常: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 获取当前游标（最后已知日志时间戳）
     */
    public String getLastTimestamp() {
        return lastTimestamp;
    }

    /**
     * 停止轮询监听
     */
    @Override
    public void close() {
        running = false;
        closed = true;
        listeners.clear();
        lastTimestamp = null;
        log.info("系统日志轮询已停止");
    }

    /**
     * 启动内部轮询线程（当未提供外部执行器时）
     */
    private void startPollingThread(DirectoryPollerEnvironment environment) {
        long intervalSec = environment != null && environment.getPollingInterval() > 0
                ? environment.getPollingInterval()
                : pollIntervalSeconds;
        java.util.concurrent.TimeUnit unit = environment != null && environment.getTimeUnit() != null
                ? environment.getTimeUnit()
                : java.util.concurrent.TimeUnit.SECONDS;

        // 若环境时间单位是 SECONDS 以外的单位，做比例换算
        long intervalMs;
        if (unit == java.util.concurrent.TimeUnit.SECONDS) {
            intervalMs = intervalSec * 1000;
        } else if (unit == java.util.concurrent.TimeUnit.MILLISECONDS) {
            intervalMs = intervalSec;
        } else {
            intervalMs = unit.toMillis(intervalSec);
        }

        Thread thread = new Thread(() -> {
            while (running) {
                try {
                    upgrade();
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("系统日志轮询线程异常: {}", e.getMessage());
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "SyslogPoller-" + (source != null ? source : "all"));
        thread.setDaemon(true);
        thread.start();
    }

    // ==================== Builder ====================

    /**
     * 创建构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 系统日志轮询监听器构建器
     */
    public static class Builder {
        private String source;
        private String pattern;
        private LogLevel minLevel;
        private int pollIntervalSeconds = 5;
        private SystemLogService logService;

        /**
         * 指定日志来源（如 Windows 的 System/Application/Security，journald 的 unit 名）
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * 指定消息匹配 glob 模式，如 {@code "*error*"}、{@code "*.dll*"}
         */
        public Builder pattern(String pattern) {
            this.pattern = pattern;
            return this;
        }

        /**
         * 指定最低日志级别过滤
         */
        public Builder minLevel(LogLevel minLevel) {
            this.minLevel = minLevel;
            return this;
        }

        /**
         * 指定轮询间隔（秒），默认 5 秒
         */
        public Builder pollIntervalSeconds(int seconds) {
            this.pollIntervalSeconds = seconds;
            return this;
        }

        /**
         * 注入自定义 SystemLogService（测试用）
         */
        public Builder service(SystemLogService service) {
            this.logService = service;
            return this;
        }

        /**
         * 构建系统日志轮询监听器
         */
        public SyslogPolledDirectory build() {
            if (logService != null) {
                return new SyslogPolledDirectory(source, pattern, minLevel, pollIntervalSeconds, logService);
            }
            return new SyslogPolledDirectory(source, pattern, minLevel, pollIntervalSeconds);
        }
    }
}
