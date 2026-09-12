package com.chua.common.support.lang.directory;

import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
* 基于 JDK {@link WatchService} 的本地文件系统目录监听器。
* <p>
* 简化设计，无需 Builder / Environment / Executor 等中间层，
* 直接链式添加监听器后调用 {@link #start()} 即可。
* </p>
*
* <p>使用示例：</p>
* <pre>{@code
* new DirectoryWatcher("/path/to/watch")
*     .addListener(new SimplePolledListener())
*     .addListener((event, observer) ->
*         System.out.println("收到事件: " + event + " -> " + observer.getFullPath()))
*     .start();
* }</pre>
*
* @author CH
* @since 2024/12/12
 */
@Slf4j
public class DirectoryWatcher {

    /**
    * 被监听的目录路径
     */
    private final String path;

    /**
    * 事件监听器列表，使用 CopyOnWriteArrayList 支持动态增删
     */
    private final List<PolledListener> listeners = new CopyOnWriteArrayList<>();

    /**
    * 运行状态标志
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
    * JDK WatchService 实例
     */
    private WatchService watchService;

    /**
    * 事件监听线程
     */
    private Thread watchThread;

    /**
    * 构造一个目录监听器。
    *
    * @param path 要监听的目录路径
     */
    public DirectoryWatcher(String path) {
        this.path = path;
    }

    /**
    * 注册事件监听器。
    *
    * @param listener 监听器
    * @return this，支持链式调用
     */
    public DirectoryWatcher addListener(PolledListener listener) {
        listeners.add(listener);
        return this;
    }

    /**
    * 启动目录监听。
    * <p>注册 JDK WatchService 并启动守护线程监听文件创建、修改、删除事件。</p>
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("目录监听器已在运行: {}", path);
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path dir = Paths.get(path);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

            watchThread = ThreadUtils.newThread(this::watchLoop, "DirWatcher-" + path);
            watchThread.setDaemon(true);
            watchThread.start();
            log.info("目录监听已启动: {}", path);
        } catch (IOException e) {
            log.error("启动目录监听失败: {}", path, e);
            running.set(false);
        }
    }

    /**
    * WatchService 事件循环，阻塞等待文件系统事件并分发。
     */
    private void watchLoop() {
        while (running.get()) {
            try {
                WatchKey key = watchService.take();
                Path watchedDir = (Path) key.watchable();

                for (WatchEvent<?> we : key.pollEvents()) {
                    if (we.kind() == StandardWatchEventKinds.OVERFLOW) {
                        log.warn("WatchService 事件溢出: {}", watchedDir);
                        continue;
                    }

                    Path fileName = (Path) we.context();
                    WatcherEvent evt = switch (we.kind().name()) {
                        case "ENTRY_CREATE" -> WatcherEvent.CREATE;
                        case "ENTRY_MODIFY" -> WatcherEvent.MODIFY;
                        default -> WatcherEvent.DELETE;
                    };

                    EventObserver observer = EventObserver.builder()
                            .currentPath(path)
                            .triggerFile(fileName.toString())
                            .eventType(evt)
                            .timestamp(LocalDateTime.now())
                            .build();

                    dispatch(evt, observer);
                }

                if (!key.reset()) {
                    log.warn("WatchKey 失效: {}", watchedDir);
                    break;
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
    * 向所有注册的监听器分发事件。
    *
    * @param evt      事件类型
    * @param observer 事件上下文
     */
    private void dispatch(WatcherEvent evt, EventObserver observer) {
        for (PolledListener l : listeners) {
            try {
                switch (evt) {
                    case CREATE -> l.onCreate(evt, observer);
                    case MODIFY -> l.onModify(evt, observer);
                    case DELETE -> l.onDelete(evt, observer);
                }
            } catch (Exception e) {
                log.error("监听器处理异常: {}", evt, e);
            }
        }
    }

    /**
    * 停止目录监听。
    * <p>关闭 WatchService 并中断监听线程。</p>
     */
    public void stop() {
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
        log.info("目录监听已停止: {}", path);
    }
}
