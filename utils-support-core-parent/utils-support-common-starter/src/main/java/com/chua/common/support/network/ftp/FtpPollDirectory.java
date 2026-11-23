package com.chua.common.support.network.ftp;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FTP 目录轮询器：定期拉取远端目录快照并与上次对比，将新增/修改/删除以
 * {@link FtpChangeEvent} 形式回调给监听器。
 *
 * <p>适用于服务端不支持 {@code NOTI} 主动推送（或走第三方 FTP 服务器）的场景。
 * 服务端为本仓库 {@link FtpServer} 时，更推荐 {@code NOTI ON} +
 * {@link FtpClient#pollNotification()} 的推送方式：实时性更好，且能识别 MOVED。</p>
 *
 * <p>快照基于 {@code NLST} + {@code SIZE} + {@code MDTM}：名称集合变化反映创建/删除，
 * 大小或修改时间变化反映内容修改。轮询无法区分「重命名」与「删除+新建」，
 * 重命名会表现为一对 DELETE/CREATE 事件。</p>
 *
 * <p><b>线程模型</b>：内部单条守护线程轮询；客户端非线程安全，轮询线程与外部调用
 * 共用同一 {@link FtpClient} 时需自行保证互斥（本类不对客户端加锁）。</p>
 *
 * <pre>{@code
 * FtpClient ftp = FtpClient.create("127.0.0.1", "user", "pass").connect();
 * FtpPollDirectory poller = new FtpPollDirectory(ftp, "/data", 3000);
 * poller.addListener(event -> System.out.println(event));
 * poller.start();
 * ...
 * poller.close();
 * ftp.close();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class FtpPollDirectory implements AutoCloseable {

    /**
     * 快照条目：文件大小 + 最后修改时间（MDTM 原文），两者任一变化视为内容修改
     */
    private record Entry(long size, String mtime) {
    }

    /**
     * 目录快照：FTP 路径 -> 条目
     */
    private record Snapshot(Map<String, Entry> entries) {
    }

    /**
     * 被轮询的 FTP 客户端
     */
    private final FtpClient client;

    /**
     * 轮询的远端目录（FTP 路径）
     */
    private final String remoteDir;

    /**
     * 轮询间隔（毫秒）
     */
    private final long intervalMillis;

    /**
     * 变更监听器列表
     */
    private final List<FtpChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 运行开关
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 轮询线程
     */
    private volatile Thread pollThread;

    /**
     * 上一次快照（start 时取基线）
     */
    private volatile Snapshot lastSnapshot;

    /**
     * 创建目录轮询器。
     *
     * @param client         已连接的 FTP 客户端
     * @param remoteDir      轮询的远端目录（如 {@code /data}）
     * @param intervalMillis 轮询间隔（毫秒），建议不低于 1000
     */
    public FtpPollDirectory(FtpClient client, String remoteDir, long intervalMillis) {
        this.client = client;
        this.remoteDir = remoteDir;
        this.intervalMillis = Math.max(1000L, intervalMillis);
    }

    /**
     * 注册变更监听器。
     *
     * @param listener 监听器
     * @return 当前实例，支持链式调用
     */
    public FtpPollDirectory addListener(FtpChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    /**
     * 移除变更监听器。
     *
     * @param listener 监听器
     * @return 当前实例，支持链式调用
     */
    public FtpPollDirectory removeListener(FtpChangeListener listener) {
        listeners.remove(listener);
        return this;
    }

    /**
     * 启动轮询：先取一次基线快照（不产生事件），随后按间隔对比。
     *
     * <p>重复调用无效；轮询线程为守护线程，JVM 退出时自动结束。</p>
     */
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // 基线快照：启动时刻的目录状态，后续轮询只报增量
        Snapshot baseline = null;
        try {
            baseline = takeSnapshot();
        } catch (Exception e) {
            log.warn("FTP 轮询基线快照失败（首轮将全量对比）: {}", e.getMessage());
        }
        lastSnapshot = baseline;
        pollThread = Thread.ofPlatform().daemon().name("ftp-poll-" + remoteDir).start(this::pollLoop);
        log.info("FTP 目录轮询启动: dir={}, interval={}ms", remoteDir, intervalMillis);
    }

    /**
     * 停止轮询（可重复调用）。
     */
    public void stop() {
        running.set(false);
        Thread thread = pollThread;
        if (thread != null) {
            thread.interrupt();
            pollThread = null;
        }
        log.info("FTP 目录轮询停止: dir={}", remoteDir);
    }

    /**
     * 停止轮询（AutoCloseable 实现）。
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * 轮询主循环。
     */
    private void pollLoop() {
        while (running.get()) {
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!running.get()) {
                break;
            }
            try {
                diffAndFire(takeSnapshot());
            } catch (Exception e) {
                // 单轮失败（网络抖动等）不终止轮询
                log.debug("FTP 轮询失败，已忽略: {}", e.getMessage());
            }
        }
    }

    /**
     * 对比新快照与上次快照，产生创建/修改/删除事件并回调监听器。
     *
     * @param current 本次快照
     */
    private void diffAndFire(Snapshot current) {
        Snapshot previous = lastSnapshot;
        lastSnapshot = current;
        if (previous == null) {
            return;
        }
        for (var entry : current.entries().entrySet()) {
            var path = entry.getKey();
            var now = entry.getValue();
            var before = previous.entries().get(path);
            if (before == null) {
                fire(FtpChangeEvent.EventType.FILE_CREATED, path, null);
            } else if (before.size() != now.size() || !before.mtime().equals(now.mtime())) {
                fire(FtpChangeEvent.EventType.FILE_MODIFIED, path, null);
            }
        }
        for (var path : previous.entries().keySet()) {
            if (!current.entries().containsKey(path)) {
                fire(FtpChangeEvent.EventType.FILE_DELETED, path, null);
            }
        }
    }

    /**
     * 回调全部监听器（单个异常互相隔离）。
     *
     * @param type         事件类型
     * @param path         FTP 路径
     * @param previousPath 原路径（轮询无 MOVED 语义，恒为 null）
     */
    private void fire(FtpChangeEvent.EventType type, String path, String previousPath) {
        var event = new FtpChangeEvent(type, path, previousPath, null);
        for (var listener : listeners) {
            try {
                listener.onChange(event);
            } catch (Exception e) {
                log.warn("FTP 轮询监听器执行失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 拉取目录快照：NLST 取名称，SIZE/MDTM 取大小与修改时间。
     *
     * @return 快照
     */
    private Snapshot takeSnapshot() {
        Map<String, Entry> entries = new HashMap<>();
        List<String> names = client.ls().path(remoteDir).exec();
        for (String name : names) {
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            var path = childPath(name);
            long size = client.size(path);
            String mtime = quietMtime(path);
            entries.put(path, new Entry(size, mtime == null ? "" : mtime));
        }
        return new Snapshot(entries);
    }

    /**
     * 读取远端条目修改时间（目录或服务器不支持 MDTM 时返回 null）。
     *
     * @param path FTP 路径
     * @return MDTM 文本，失败返回 null
     */
    private String quietMtime(String path) {
        try {
            return client.modificationTime(path);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 拼接子条目 FTP 路径。
     *
     * @param name 目录内条目名
     * @return 完整 FTP 路径
     */
    private String childPath(String name) {
        if (remoteDir.endsWith("/")) {
            return remoteDir + name;
        }
        return remoteDir + "/" + name;
    }

    /**
     * 获取轮询的远端目录。
     */
    public String getRemoteDir() {
        return remoteDir;
    }

    /**
     * 获取轮询间隔（毫秒）。
     */
    public long getIntervalMillis() {
        return intervalMillis;
    }

    /**
     * 轮询是否运行中。
     */
    public boolean isRunning() {
        return running.get();
    }
}
