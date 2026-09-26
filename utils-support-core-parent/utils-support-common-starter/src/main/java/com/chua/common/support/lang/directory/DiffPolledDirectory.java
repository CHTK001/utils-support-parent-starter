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
     * 事件监听器与运行状态的公共支撑件
     */
    protected final PolledDirectorySupport support = new PolledDirectorySupport(getClass().getSimpleName());

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
     * 开始
     */
    @Override
    public void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor executor) {
        // 重复 start 会在同一数据源上叠加多个轮询循环，第二次起静默忽略
        if (!support.markRunning()) {
            log.warn("轮询目录已处于运行状态，忽略重复启动: {}", listenPath);
            return;
        }
        this.environment = environment;

        // 初始化缓存快照
        List<T> items = listAndModified(listenPath);
        if (items != null) {
            for (T item : items) {
                cache.put(getFileName(item), normalized(getModified(item)));
            }
        }

        // 由执行器驱动定时轮询；支持 null（使用默认 VirtualThreadPollerExecutor）
        if (executor == null) {
            executor = new com.chua.common.support.lang.directory.executor.VirtualThreadPollerExecutor(this, environment);
        }
        executor.start();
    }

    /**
     * Upgrade
     */
    @Override
    public void upgrade() {
        List<T> current;
        try {
            current = listAndModified(listenPath);
        } catch (Exception e) {
            // 拉取失败不等于条目全部消失，直接当本轮无变更，避免把远端抖动误报成批量删除
            log.error("拉取快照失败: {}", listenPath, e);
            support.fireError(listenPath, null, e);
            return;
        }
        if (current == null) {
            return;
        }

        Map<String, T> curMap = new HashMap<>();
        for (T item : current) {
            String name = getFileName(item);
            curMap.put(name, item);
            // 子类的 getModified 允许返回 null（如时间戳不可得），统一归一为 0
            Long modified = normalized(getModified(item));
            Long prev = cache.get(name);
            if (prev == null) {
                cache.put(name, modified);
                support.fire(listenPath, WatcherEvent.CREATE, name, environment);
            } else if (!modified.equals(prev)) {
                cache.put(name, modified);
                support.fire(listenPath, WatcherEvent.MODIFY, name, environment);
            }
        }

        // 快照 key 集合，避免在遍历 cache 时修改 cache
        for (String name : new ArrayList<>(cache.keySet())) {
            if (!curMap.containsKey(name)) {
                cache.remove(name);
                support.fire(listenPath, WatcherEvent.DELETE, name, environment);
            }
        }
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
     * 关闭
     */
    @Override
    public void close() {
        support.markStopped();
        cache.clear();
        support.clearListeners();
    }

    /**
     * 归一化修改时间戳，把子类返回的 {@code null} 收敛为 {@code 0L}。
     *
     * <p>{@code ConcurrentHashMap} 不接受 null 值，且 {@code getModified(item).equals(prev)}
     * 在时间戳不可得时抛 NPE，导致整轮轮询中断、后续条目全部漏报。</p>
     *
     * @param modified 子类返回的修改时间戳
     * @return 非空的时间戳
     */
    private static Long normalized(Long modified) {
        return modified == null ? 0L : modified;
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
