package com.chua.common.support.lang.directory;

import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 差异对比轮询目录抽象基类，实现 {@link PolledDirectory} 接口。
 * <p>
 * 适用于无法使用操作系统事件机制的数据源（如 FTP、SFTP、数据库 CDC），
 * 通过快照对比（diff）发现新增、修改、删除的条目。
 * </p>
 * <p>
 * 子类需实现三个抽象方法：
 * <ul>
 *   <li>{@link #listAndModified(String)} — 获取当前所有条目及修改时间</li>
 *   <li>{@link #getFileName(Object)} — 提取条目名称</li>
 *   <li>{@link #getModified(Object)} — 提取条目修改时间戳</li>
 * </ul>
 * </p>
 * <pre>{@code
 * // FTP 示例
 * DiffPolledDirectory<FtpFile> poller = new DiffPolledDirectory<>("/remote") {
 *     protected List<FtpFile> listAndModified(String path) { return ftpClient.listFiles(path); }
 *     protected String getFileName(FtpFile f) { return f.getName(); }
 *     protected Long getModified(FtpFile f) { return f.getTimestamp(); }
 * };
 * poller.addListener(new SimplePolledListener());
 *
 * DirectoryPollerEnvironment env = new DirectoryPollerEnvironment(
 *     Set.of(CREATE, MODIFY, DELETE), 5, TimeUnit.SECONDS);
 * VirtualThreadPollerExecutor executor = new VirtualThreadPollerExecutor(poller, env);
 * poller.start(env, executor);
 * }</pre>
 *
 * @param <T> 条目类型
 * @author CH
 * @since 2024/12/12
 */
@Slf4j
public abstract class DiffPolledDirectory<T> implements PolledDirectory {

    /**
     * 条目名称 -> 修改时间戳 的缓存映射
     */
    protected final Map<String, Long> cache = new ConcurrentHashMap<>();

    /**
     * 被监听的路径
     */
    protected final String listenPath;

    /**
     * 事件监听器列表
     */
    private final List<PolledListener> listeners = new ArrayList<>();

    /**
     * 环境配置
     */
    protected DirectoryPollerEnvironment environment;

    /**
     * 构造差异对比轮询器。
     *
     * @param listenPath 被监听的路径
     */
    public DiffPolledDirectory(String listenPath) {
        this.listenPath = listenPath;
    }

    @Override
    /**
     * 添加Listener
    */
    public void addListener(PolledListener listener) {
        listeners.add(listener);
    }

    @Override
    /**
     * 开始
    */
    public void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor executor) {
        this.environment = environment;

        // 初始化缓存快照
        List<T> items = listAndModified(listenPath);
        if (items != null) {
            for (T item : items) {
                cache.put(getFileName(item), getModified(item));
            }
        }

        // 由执行器驱动定时轮询；支持 null（使用默认 VirtualThreadPollerExecutor）
        if (executor == null) {
            executor = new com.chua.common.support.lang.directory.executor.VirtualThreadPollerExecutor(this, environment);
        }
        executor.start();
    }

    @Override
    /**
     * Upgrade
    */
    public void upgrade() {
        List<T> current = listAndModified(listenPath);
        if (current == null) {
            return;
        }

        Map<String, T> curMap = new HashMap<>();
        for (T item : current) {
            String name = getFileName(item);
            curMap.put(name, item);
            Long prev = cache.get(name);
            if (prev == null) {
                cache.put(name, getModified(item));
                fire(WatcherEvent.CREATE, name);
            } else if (!getModified(item).equals(prev)) {
                cache.put(name, getModified(item));
                fire(WatcherEvent.MODIFY, name);
            }
        }

        for (String name : cache.keySet()) {
            if (!curMap.containsKey(name)) {
                cache.remove(name);
                fire(WatcherEvent.DELETE, name);
            }
        }
    }

    /**
     * 向所有注册的监听器分发事件。
     *
     * @param event    事件类型
     * @param fileName 触发事件的文件名
     */
    private void fire(WatcherEvent event, String fileName) {
        EventObserver observer = EventObserver.builder()
                .currentPath(listenPath)
                .triggerFile(fileName)
                .eventType(event)
                .build();

        for (PolledListener l : listeners) {
            try {
                switch (event) {
                    case CREATE -> l.onCreate(event, observer);
                    case MODIFY -> l.onModify(event, observer);
                    case DELETE -> l.onDelete(event, observer);
                    case OVERFLOW -> l.onOverflow(event, observer);
                    default -> throw new IllegalArgumentException("未知事件类型: " + event);
                }
            } catch (Exception e) {
                log.error("监听器分发异常: {}", event, e);
            }
        }
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        cache.clear();
        listeners.clear();
    }

    /**
     * 获取指定路径下的所有条目。
     *
     * @param path 路径
     * @return 条目列表，返回 null 表示获取失败
     */
    protected abstract List<T> listAndModified(String path);

    /**
     * 从条目中提取名称。
     *
     * @param item 条目
     * @return 条目名称
     */
    protected abstract String getFileName(T item);

    /**
     * 从条目中提取最后修改时间戳（毫秒）。
     *
     * @param item 条目
     * @return 修改时间戳
     */
    protected abstract Long getModified(T item);
}
