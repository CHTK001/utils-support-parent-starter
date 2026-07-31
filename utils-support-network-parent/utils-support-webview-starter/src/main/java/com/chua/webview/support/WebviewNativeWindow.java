package com.chua.webview.support;

import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.webview.support.webview.WebViewWindow;
import dev.webview.Webview;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 webview_java 的 WebView 实现。
 * <p>
 * 使用 JNA 技术封装原生 WebView：
 * <ul>
 *   <li>Windows: WebView2 (Edge Chromium)</li>
 *   <li>macOS: WKWebView</li>
 *   <li>Linux: WebKitGTK</li>
 * </ul>
 * </p>
 * <p>
 * 通过 {@link ConditionalOnClass} 注解，仅在 classpath 中存在
 * {@code dev.webview.Webview} 类时生效，支持 SPI 自动装配。
 * </p>
 *
 * @author CH
 * @since 2025
 */
@ConditionalOnClass("dev.webview.Webview")
@RequiredArgsConstructor
public class WebviewNativeWindow implements WebViewWindow {

    /**
     * 日志记录器实例。
     */
    private static final Logger log = LoggerFactory.getLogger(WebviewNativeWindow.class);

    /**
     * 底层 Webview 实例。
     */
    private Webview webview;

    /**
     * 运行事件循环的线程。
     */
    private Thread runThread;

    /**
     * 打开 WebView 窗口并加载指定 URL。
     *
     * @param url    要加载的 URL 地址
     * @param title  窗口标题
     * @param width  窗口宽度
     * @param height 窗口高度
     */
    @Override
    public void open(String url, String title, int width, int height) {
        try {
            // 创建新的 Webview 实例（非共享模式）
            this.webview = new Webview(true);
            this.webview.setTitle(title);
            this.webview.setSize(width, height);
            this.webview.loadURL(url);

            // 启动事件循环线程
            this.runThread = ThreadUtils.newThread(this.webview, "Webview Event Loop");
            this.runThread.setDaemon(false);
            this.runThread.start();
        } catch (Throwable e) {
            log.error("Failed to open webview_java", e);
        }
    }

    /**
     * 关闭 WebView 窗口并释放资源。
     */
    @Override
    public void close() {
        if (this.webview != null) {
            try {
                this.webview.close();
            } catch (Exception e) {
                log.warn("Error closing webview", e);
            }
            this.webview = null;
        }
    }
}
