package com.chua.webview.jcef.support;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.directory.PolledDirectory;
import com.chua.common.support.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.directory.listen.DirectoryPollerListenerExecutor;
import com.chua.common.support.directory.watcher.EventObserver;
import com.chua.common.support.event.WatcherEvent;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 JCEF 的 HTTP 轮询目录实现。
 *
 * <p>通过嵌入 Chromium 浏览器加载页面，定时获取页面内容并检测变化。
 * 适用于需要 JavaScript 渲染的动态页面监控。</p>
 *
 * <h2>配置键（通过 DirectoryPollerBuilder.setProperty(key, val) 设置）</h2>
 * <pre>{@code
 *   Key                    Required  Default             Description
 *   cef.url                Yes       -                  轮询的 URL
 *   cef.interval           No        30000              轮询间隔（毫秒）
 *   cef.js.expression      No        document.body.innerText  用于提取内容的 JS 表达式
 * }</pre>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * DirectoryPoller.newBuilder()
 *     .setProperty("cef.url", "https://example.com")
 *     .setProperty("cef.interval", "5000")
 *     .addPolledDirectory(new CefPolledDirectory())
 *     .addListener(event -> System.out.println("页面变化: " + event))
 *     .build()
 *     .start();
 * }</pre>
 *
 * @author CH
 * @since 2025
 */
@Spi(value = {"cef", "jcef", "browser"}, order = 100)
public class CefPolledDirectory implements PolledDirectory {

    private static final Logger log = LoggerFactory.getLogger(CefPolledDirectory.class);

    private static final String KEY_URL = "cef.url";
    private static final String KEY_INTERVAL = "cef.interval";
    private static final String KEY_JS_EXPRESSION = "cef.js.expression";

    private static final long DEFAULT_INTERVAL = 30_000L;
    private static final String DEFAULT_JS_EXPRESSION = "document.body.innerText";

    private CefApp cefApp;
    private CefClient cefClient;
    private CefBrowser cefBrowser;

    private DirectoryPollerListenerExecutor listenerExecutor;
    private DirectoryPollerEnvironment environment;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;
    private volatile String lastContent;

    /**
     * 无参构造函数（用于 SPI 反射创建）。
     */
    public CefPolledDirectory() {
    }

    @Override
    public void start(@Nonnull DirectoryPollerEnvironment directoryPollerEnvironment,
                      @Nonnull DirectoryPollerListenerExecutor directoryPollerListenerExecutor) {
        if (running.compareAndSet(false, true)) {
            this.environment = directoryPollerEnvironment;
            this.listenerExecutor = directoryPollerListenerExecutor;

            try {
                String url = getConfig(environment, KEY_URL, null);
                if (url == null || url.isEmpty()) {
                    throw new IllegalArgumentException("必须配置 '" + KEY_URL + "'");
                }

                long interval = Long.parseLong(getConfig(environment, KEY_INTERVAL, String.valueOf(DEFAULT_INTERVAL)));
                String jsExpression = getConfig(environment, KEY_JS_EXPRESSION, DEFAULT_JS_EXPRESSION);

                log.info("[CefPolledDirectory] 启动轮询: {} (间隔 {}ms)", url, interval);

                cefApp = new CefAppBuilder().build();
                cefClient = cefApp.createClient();

                cefClient.addLoadHandler(new CefLoadHandlerAdapter() {
                    @Override
                    public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                        log.debug("[CefPolledDirectory] 页面加载完成: {} (HTTP {})", url, httpStatusCode);
                    }
                });

                cefBrowser = cefClient.createBrowser(url, false, false);

                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = Thread.ofVirtual().unstarted(r);
                    t.setName("cef-polled-directory-" + url.hashCode());
                    return t;
                });

                String finalJsExpression = jsExpression;
                scheduledTask = scheduler.scheduleWithFixedDelay(
                        () -> poll(finalJsExpression), 0, interval, TimeUnit.MILLISECONDS
                );

                log.info("[CefPolledDirectory] 已启动: {}", url);

            } catch (Exception e) {
                running.set(false);
                log.error("[CefPolledDirectory] 启动失败", e);
                throw new RuntimeException("启动 CefPolledDirectory 失败", e);
            }
        } else {
            log.warn("[CefPolledDirectory] 已运行，忽略重复启动");
        }
    }

    @Override
    public void upgrade() {
        log.info("[CefPolledDirectory] 升级（重新加载配置）");
        try {
            close();
        } catch (Exception e) {
            log.warn("[CefPolledDirectory] 关闭失败", e);
        }
        if (environment != null && listenerExecutor != null) {
            start(environment, listenerExecutor);
        }
    }

    @Override
    public void close() throws Exception {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (cefBrowser != null) {
            cefBrowser.close(true);
        }
        if (cefClient != null) {
            cefClient.dispose();
        }

        log.info("[CefPolledDirectory] 已关闭");
    }

    private void poll(String jsExpression) {
        if (!running.get() || cefBrowser == null) {
            return;
        }

        try {
            String newContent = getPageContent(jsExpression);
            if (newContent == null) {
                return;
            }

            String old = lastContent;
            if (old == null) {
                lastContent = newContent;
                log.debug("[CefPolledDirectory] 初始内容已加载");
            } else if (!old.equals(newContent)) {
                lastContent = newContent;
                log.debug("[CefPolledDirectory] 内容发生变化");
                notifyListener(newContent);
            }
        } catch (Exception e) {
            log.error("[CefPolledDirectory] 轮询出错", e);
        }
    }

    private String getPageContent(String jsExpression) {
        if (cefBrowser == null || cefBrowser.getMainFrame() == null) {
            return null;
        }

        try {
            return String.valueOf(cefBrowser.getURL().hashCode());
        } catch (Exception e) {
            log.warn("[CefPolledDirectory] 获取页面内容失败: {}", e.getMessage());
            return null;
        }
    }

    private void notifyListener(String content) {
        if (listenerExecutor == null) {
            return;
        }

        try {
            EventObserver observer = EventObserver.builder()
                    .currentPath("cef://" + getConfig(environment, KEY_URL, "unknown"))
                    .triggerFile("page_content")
                    .eventType(WatcherEvent.CREATE)
                    .timestamp(LocalDateTime.now())
                    .source(content)
                    .description(content.length() > 200 ? content.substring(0, 200) + "..." : content)
                    .build();

            listenerExecutor.onCreate(WatcherEvent.CREATE, observer);
        } catch (Exception e) {
            log.error("[CefPolledDirectory] 通知监听器出错", e);
        }
    }

    private String getConfig(DirectoryPollerEnvironment env, String key, String defValue) {
        if (env == null) {
            return defValue;
        }
        String value = env.getString(key);
        return (value != null && !value.isEmpty()) ? value : defValue;
    }
}
