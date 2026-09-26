package com.chua.common.support.lang.directory.provider;

import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledDirectorySupport;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于 JDK WatchService 的文件系统目录轮询实现。
 * <p>
 * 实现 {@link PolledDirectory} 接口，通过操作系统事件驱动监听，无需轮询。
 * </p>
 * <p>使用示例：</p>
 * <pre>{@code
 * FileSystemPolledDirectory polled = new FileSystemPolledDirectory("/data/logs");
 * polled.addListener(new SimplePolledListener());
 * polled.start(env);
 * }</pre>
 *
 * @author CH
 * @since 2024/12/12
 */
@Slf4j
public class FileSystemPolledDirectory implements PolledDirectory {

    /**
     * 被监听的目录路径
     */
    private final String path;

    /**
     * 事件监听器与运行状态的公共支撑件
     */
    private final PolledDirectorySupport support = new PolledDirectorySupport(getClass().getSimpleName());

    /**
     * JDK WatchService
     */
    private WatchService watchService;

    /**
     * 事件监听线程
     */
    private Thread watchThread;

    /**
     * WatchKey 与目录路径的映射
     */
    private final Map<WatchKey, Path> watchKeys = new HashMap<>();

    /**
     * 环境配置
     */
    private DirectoryPollerEnvironment environment;

    /**
     * 构造文件系统目录轮询实现。
     *
     * @param path 被监听的目录路径
     */
    public FileSystemPolledDirectory(String path) {
        this.path = path;
    }

    /**
     * 是否DelegatedOperatingSystem
     */
    @Override
    public boolean isDelegatedOperatingSystem() {
        return true;
    }

    /**
     * 添加Listener
     */
    @Override
    public void addListener(PolledListener listener) {
        support.addListener(listener);
    }

    /**
     * 移除Listener
     *
     * @param listener 监听器
     * @return 移除前该监听器是否已注册
     */
    public boolean removeListener(PolledListener listener) {
        return support.removeListener(listener);
    }

    /**
     * 是否处于运行中
     */
    @Override
    public boolean isRunning() {
        return support.isRunning();
    }

    /**
     * 停止
     */
    @Override
    public void stop() {
        close();
    }

    /**
     * 开始
     */
    @Override
    public void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor executor) {
        if (!support.markRunning()) {
            log.warn("文件系统目录监听已在运行，忽略重复启动: {}", path);
            return;
        }

        this.environment = environment;

        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path dir = Paths.get(path);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            WatchKey key = dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watchKeys.put(key, dir);

            watchThread = ThreadUtils.newThread(this::watchLoop, "FileSystemWatcher-" + path);
            watchThread.setDaemon(true);
            watchThread.start();

            log.info("文件系统目录监听已启动: {}", path);
        } catch (IOException e) {
            log.error("启动文件系统目录监听失败: {}", path, e);
            support.markStopped();
        }
    }

    /**
     * Upgrade
     */
    @Override
    public void upgrade() {
        // WatchService 由事件驱动，无需轮询
    }

    /**
     * WatchService 事件循环。
     */
    private void watchLoop() {
        while (support.isRunning()) {
            try {
                WatchKey key = watchService.take();
                Path watchedDir = watchKeys.get(key);
                if (watchedDir == null) {
                    key.reset();
                    continue;
                }

                for (WatchEvent<?> we : key.pollEvents()) {
                    if (we.kind() == StandardWatchEventKinds.OVERFLOW) {
                        support.fire(overflowObserver(watchedDir));
                        continue;
                    }

                    Path fileName = (Path) we.context();
                    WatcherEvent evt = switch (we.kind().name()) {
                        case "ENTRY_CREATE" -> WatcherEvent.CREATE;
                        case "ENTRY_MODIFY" -> WatcherEvent.MODIFY;
                        default -> WatcherEvent.DELETE;
                    };

                    if (environment != null && !environment.hasEvent(evt)) {
                        continue;
                    }

                    support.fire(EventObserver.builder()
                            .currentPath(watchedDir.toString())
                            .triggerFile(fileName.toString())
                            .eventType(evt)
                            .build());
                }

                if (!key.reset()) {
                    log.warn("WatchKey 失效，移除: {}", watchedDir);
                    watchKeys.remove(key);
                    if (watchKeys.isEmpty()) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (RuntimeException e) {
                // 单个事件处理异常不能终止整个监听循环，否则目录后续变更全部静默丢失
                log.error("文件系统目录监听事件处理异常: {}", path, e);
                support.fireError(path, null, e);
            }
        }
    }

    /**
     * 构造事件队列溢出时的观察者。
     *
     * @param watchedDir 溢出的目录
     * @return 观察者
     */
    private EventObserver overflowObserver(Path watchedDir) {
        return EventObserver.builder()
                .currentPath(watchedDir.toString())
                .triggerFile("OVERFLOW")
                .eventType(WatcherEvent.OVERFLOW)
                .build();
    }

    /**
     * 关闭
     */
    @Override
    public void close() {
        support.markStopped();
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
        watchKeys.clear();
        support.clearListeners();
        log.info("文件系统目录监听已停止: {}", path);
    }
}
