package com.chua.common.support.lang.script.marker.listener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.chua.common.support.utils.ThreadUtils;

/**
 * URL 脚本源码监听器。
 *
 * <p>通过定时轮询远程 URL 获取脚本源码，检测内容变化。
 * 适合从配置中心、Web 服务等远程位置动态加载脚本的场景。</p>
 *
 * <p>特性：
 * <ul>
 *   <li>后台定时拉取 URL 内容，线程为 daemon 模式</li>
 *   <li>通过内容哈希比较判断是否变更</li>
 *   <li>支持手动关闭，释放定时任务资源</li>
 * </ul></p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see Listener
 */
public class UrlScriptListener implements Listener {

    /**
     * 脚本内容 URL
     */
    private final URL url;

    /**
     * 轮询周期（毫秒）
     */
    private final long periodMillis;

    /**
     * 定时任务线程池（单线程，daemon）
     */
    private final java.util.concurrent.ScheduledExecutorService scheduler;

    /**
     * 上次拉取的脚本内容
     */
    private final AtomicReference<String> lastContent = new AtomicReference<>();

    /**
     * 内容是否发生变化的标记
     */
    private final AtomicBoolean changed = new AtomicBoolean(false);

    /**
     * 定时任务句柄
     */
    private volatile java.util.concurrent.ScheduledFuture<?> future;

    /**
     * HTTP 连接超时上限（毫秒），取 periodMillis 与 5 秒的较小值
     */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /**
     * 构造 URL 脚本监听器。
     *
     * @param url          脚本内容 URL
     * @param periodMillis 轮询周期，单位毫秒
     */
    public UrlScriptListener(URL url, long periodMillis) {
        this.url = url;
        this.periodMillis = periodMillis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new UrlScriptThreadFactory());
        this.future = scheduler.scheduleAtFixedRate(
                this::fetch, 0, periodMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    /** 是否Change */
    public boolean isChange() {
        boolean result = changed.getAndSet(false);
        if (lastContent.get() == null) {
            fetch();
            return true;
        }
        return result;
    }

    @Override
    /** 获取Source */
    public String getSource() {
        return lastContent.get();
    }

    /**
     * 关闭监听器，取消定时任务。
     *
     * <p>应在不再需要监听时调用，避免 daemon 线程残留。</p>
     */
    public void close() {
        if (future != null) {
            future.cancel(false);
        }
        scheduler.shutdownNow();
    }

    /**
     * 执行一次远程内容拉取。
     */
    private void fetch() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            int timeout = (int) Math.min(periodMillis, CONNECT_TIMEOUT_MS);
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                String newContent = sb.toString();
                String oldContent = lastContent.get();
                if (oldContent == null || !oldContent.equals(newContent)) {
                    lastContent.set(newContent);
                    changed.set(true);
                }
            }
        } catch (Exception e) {
            if (lastContent.get() == null) {
                lastContent.set("");
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * URL 脚本监听器专用线程工厂。
     *
     * <p>创建 daemon 线程，避免阻止 JVM 正常退出。</p>
     */
    private static class UrlScriptThreadFactory implements ThreadFactory {
        @Override
        /** NewThread */
        public Thread newThread(Runnable r) {
            Thread thread = ThreadUtils.newThread(r, "url-script-listener-" + r.hashCode());
            thread.setDaemon(true);
            return thread;
        }
    }
}
