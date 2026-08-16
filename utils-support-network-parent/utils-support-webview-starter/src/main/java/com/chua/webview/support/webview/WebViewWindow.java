package com.chua.webview.support.webview;

/**
 * WebView 窗口接口定义。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface WebViewWindow {

    /**
     * 打开指定的 WebView 窗口。
     *
     * @param url    要加载的网页 URL 地址。
     * @param title  窗口显示的标题文本。
     * @param width  窗口的宽度像素值。
     * @param height 窗口的高度像素值。
     */
    void open(String url, String title, int width, int height);

    /**
     * 检查当前实现是否支持进程间通信 (IPC) 机制。
     *
     * @return 如果支持 IPC 则返回 true，否则返回 false。
     */
    default boolean supportsIpc() {
        return false;
    }

    /**
     * 关闭当前的 WebView 窗口。
     */
    void close();
}
