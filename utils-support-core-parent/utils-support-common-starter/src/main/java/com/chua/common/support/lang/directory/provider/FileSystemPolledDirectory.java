package com.chua.common.support.lang.directory.provider;

import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledDirectory;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
    * 事件监听器列表
     */
    private final List<PolledListener> listeners = new CopyOnWriteArrayList<>();

    /**
    * 运行状态
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

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

    @Override
    /** 是否DelegatedOperatingSystem */
    public boolean isDelegatedOperatingSystem() {
        return true;
    }

    @Override
    /** 添加Listener */
    public void addListener(PolledListener listener) {
        listeners.add(listener);
    }

    @Override
    /** 开始 */
    public void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor executor) {
        if (!running.compareAndSet(false, true)) {
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
            running.set(false);
        }
    }

    @Override
    /** Upgrade */
    public void upgrade() {
        // WatchService 由事件驱动，无需轮询
    }

    /**
    * WatchService 事件循环。
     */
    private void watchLoop() {
        while (running.get()) {
            try {
                WatchKey key = watchService.take();
                Path watchedDir = watchKeys.get(key);
                if (watchedDir == null) {
                    key.reset();
                    continue;
                }

                for (WatchEvent<?> we : key.pollEvents()) {
                    if (we.kind() == StandardWatchEventKinds.OVERFLOW) {
                        fire(WatcherEvent.OVERFLOW, watchedDir.toString(), "OVERFLOW");
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

                    fire(evt, watchedDir.toString(), fileName.toString());
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
            }
        }
    }

    /**
    * 向所有监听器分发事件。
    *
    * @param event      事件类型
    * @param currentPath 当前目录路径
    * @param triggerFile 触发文件名
     */
    private void fire(WatcherEvent event, String currentPath, String triggerFile) {
        var observer = com.chua.common.support.lang.directory.EventObserver.builder()
                .currentPath(currentPath)
                .triggerFile(triggerFile)
                .eventType(event)
                .timestamp(LocalDateTime.now())
                .build();

        for (PolledListener l : listeners) {
            try {
                switch (event) {
                    case CREATE -> l.onCreate(event, observer);
                    case MODIFY -> l.onModify(event, observer);
                    case DELETE -> l.onDelete(event, observer);
                    case OVERFLOW -> l.onOverflow(event, observer);
                }
            } catch (Exception e) {
                log.error("监听器分发异常: {}", event, e);
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        running.set(false);
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
        log.info("文件系统目录监听已停止: {}", path);
    }
}
