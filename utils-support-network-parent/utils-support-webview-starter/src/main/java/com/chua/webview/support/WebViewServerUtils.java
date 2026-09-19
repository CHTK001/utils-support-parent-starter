package com.chua.webview.support;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.Server;
import com.chua.webview.support.webview.WebViewWindow;

/**
 * webview服务器工具类，用于根据协议类型打开webview窗口。
 * 该类提供静态方法，根据给定的服务器和窗口配置启动服务并显示窗口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WebViewServerUtils {

    /**
     * 打开webview窗口。
     *
     * @param window  webview窗口实例，用于显示内容。
     * @param server  协议服务器实例，提供URL和协议类型信息。
     * @param title   窗口标题。
     * @param width   窗口宽度。
     * @param height  窗口高度。
     * @throws UnsupportedOperationException 如果窗口不支持IPC协议但服务器使用IPC协议时抛出。
     */
    public static void open(WebViewWindow window, Server server, String title, int width, int height) {
        final ProtocolType type = server.getProtocolType();

        if (type == ProtocolType.IPC) {
            if (!window.supportsIpc()) {
                throw new UnsupportedOperationException(
                        "当前WebView窗口不支持IPC协议，请使用JcefWebviewWindow实现。");
            }
            return;
        }

        server.startIfNeeded();
        window.open(server.getServerUrl(), title, width, height);
    }

    /**
     * 私有构造函数，防止实例化。
     */
    private WebViewServerUtils() {
    }
}
