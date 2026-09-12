package com.chua.webview.jcef.support;

import com.chua.common.support.lang.json.Json;
import com.chua.webview.support.webview.WebViewWindow;
import com.chua.webview.jcef.support.internal.IpcProtocolServer;
import com.chua.webview.jcef.support.internal.ProtocolServer;
import com.chua.webview.jcef.support.internal.ProtocolType;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.browser.CefMessageRouter.CefMessageRouterConfig;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.callback.CefQueryCallback;
import lombok.extern.slf4j.Slf4j;

import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
   * JCEF (Java 铬 Embedded 框架) webview
 * <p>
 *        <a href="https://github.com/jcefmaven/jcefmaven">JCEF Maven</a>   
   * 铬
 * <ul>
 *   <li>          HTML5 / CSS3 / JavaScript       </li>
 *   <li>DevTools       </li>
 *   <li>          (Windows / macOS / Linux)</li>
 *   <li>IPC                 JS   Java              {@link IpcProtocolServer}          </li>
 * </ul>
   * webview_Java     webview2/wkwebview/webkitgtk
   * JCEF                 铬
 * </p>
 * <p>
 *        {@link ConditionalOnClass}                       classpath          
 * {@code org.cef.CefApp}       SPI                         
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SuppressWarnings("unused")
@Slf4j
public class JcefWebviewWindow implements WebViewWindow {

    /**
     * ipc 页面
     */
    private static final String IPC_PAGE =
            "<!DOCTYPE html><html><body style='background:#1e1e1e;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>" +
            "<div style='text-align:center'>" +
            "<h2>JCEF IPC Bridge</h2>" +
            "<p style='color:#888'>IPC protocol server is ready</p>" +
            "</div></body></html>";

    /**
     * cef App
     */
    private CefApp cefApp;
    /**
     * 客户端实例
     */
    private CefClient client;
    /**
     * 浏览器实例
     */
    private CefBrowser browser;
    /**
      * 帧
     */
    private Frame frame;
    /**
      * ipc 服务端
     */
    private IpcProtocolServer ipcServer;

    @Override
    /** 打开 */
    public void open(String url, String title, int width, int height) {
        try {
            cefApp = new CefAppBuilder().build();
            client = cefApp.createClient();
            browser = client.createBrowser(url, false, false);

            frame = new Frame(title);
            frame.add(browser.getUIComponent());
            frame.setSize(width, height);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                /** 窗口关闭 */
                public void windowClosing(WindowEvent e) {
                    close();
                }
            });
            frame.setVisible(true);
        }
catch (Throwable e) {
            log.error("Failed to open JCEF webview", e);
        }
    }

    @Override
    /** 支持ipc */
    public boolean supportsIpc() {
        return true;
    }

    /**
     * 打开
     *
     * @param server 服务端
     * @param title title
     * @param width width
     * @param height height
     */
    public void open(ProtocolServer server, String title, int width, int height) {
        ProtocolType type = server.getProtocolType();
        if (type == ProtocolType.IPC) {
            openIpc(server, title, width, height);
        } else {
            String url = server.getServerUrl();
            open(url, title, width, height);
        }
    }

    /**
      * IPC              webview
     * <p>
      * IPC                 cef消息router        {@code javaBridge} JS
     *              {@code window.javaBridge.send(JSON.stringify({path, method, body}), callback)}
     *     {@link IpcProtocolServer#handleMessage}                            
     * </p>
     * @param server 服务端
     * @param title title
     * @param width width
     * @param height height
     */
    private void openIpc(ProtocolServer server, String title, int width, int height) {
        if (!(server instanceof IpcProtocolServer)) {
            log.error("IPC protocol requires IpcProtocolServer, got: {}", server.getClass().getName());
            return;
        }
        this.ipcServer = (IpcProtocolServer) server;

        try {
            server.startIfNeeded();
            cefApp = new CefAppBuilder().build();
            client = cefApp.createClient();

            //        JS   Java       
            CefMessageRouterConfig config = new CefMessageRouterConfig("javaBridge", "javaBridgeCancel");
            CefMessageRouter router = CefMessageRouter.create(config);
            router.addHandler(new IpcMessageRouterHandler(this.ipcServer), true);
            client.addMessageRouter(router);

 // IPC          数据 URL
            browser = client.createBrowser("data:text/html;base64," +
                    java.util.Base64.getEncoder().encodeToString(IPC_PAGE.getBytes(StandardCharsets.UTF_8)),
                    false, false);

            frame = new Frame(title);
            frame.add(browser.getUIComponent());
            frame.setSize(width, height);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                /** 窗口关闭 */
                public void windowClosing(WindowEvent e) {
                    close();
                }
            });
            frame.setVisible(true);
        }
catch (Throwable e) {
            log.error("Failed to open JCEF IPC webview", e);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        try {
            if (browser != null) {
                browser.close(true);
                browser = null;
            }
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
        }
catch (Exception e) {
            log.warn("Error closing JCEF webview", e);
        }
finally {
            client = null;
            ipcServer = null;
        }
    }

    /**
      * JCEF cef消息router                 JS                 {@link IpcProtocolServer}
     * @author CH
     * @since 4.0.0
     */
    private static class IpcMessageRouterHandler extends CefMessageRouterHandlerAdapter {

        /**
          * ipc 服务端
         */
        private final IpcProtocolServer ipcServer;

        IpcMessageRouterHandler(IpcProtocolServer ipcServer) {
            this.ipcServer = ipcServer;
        }

        @Override
        /**
         * On查询
         * @param browser browser
         * @param frame 帧
         * @param queryId 查询标识
         * @param request 请求
         * @param persistent persistent
         * @param callback callback
         */
        public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                               String request, boolean persistent, CefQueryCallback callback) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = Json.fromJson(request, Map.class);
                String path = msg != null ? (String) msg.getOrDefault("path", "/") : "/";
                String method = msg != null ? (String) msg.getOrDefault("method", "POST") : "POST";
                Object bodyObj = msg != null ? msg.get("body") : null;
                String body = bodyObj != null ? bodyObj.toString() : "";

                String response = ipcServer.handleMessage("jcef-ipc", path, body);
                callback.success(response);
            }
catch (Exception e) {
                log.error("IPC message handler error", e);
                callback.failure(-1, e.getMessage());
            }
            return true;
        }
    }
}