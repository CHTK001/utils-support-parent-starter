package com.chua.webview.jcef.support;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.network.support.network.protocol.ServerSetting;
import com.chua.network.support.network.protocol.request.DefaultServletRequest;
import com.chua.network.support.network.protocol.request.ServletResponse;
import com.chua.network.support.network.protocol.server.AbstractProtocolServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;


/**
 * IPC                
 * <p>
 *                                JCEF JS   Java                      
 *              {@link #doHandle}        ServletFilter                          HTTP                                              
 * </p>
 *
 * <pre>
 *                
 * //        IPC                
 * IpcProtocolServer ipc = new IpcProtocolServer(
 *     ServerSetting.builder().protocol("ipc").build()
 *);
 * ipc.start();
 * ipc.registerMapping("/api/hello", (req, res) -> {
 *     res.setBodyString("{\"msg\":\"hello\"}");
 * });
 *
 * //        JCEF WebView       
 * JcefWebviewWindow window = new JcefWebviewWindow();
 * window.open(ipc, "IPC Demo", 800, 600);
 * </pre>
 *
 * @author CH
 * @since 2025
 */
@Spi(value = "ipc", order = Integer.MAX_VALUE)
public class IpcProtocolServer extends AbstractProtocolServer {

    private static final Logger log = LoggerFactory.getLogger(IpcProtocolServer.class);

    public IpcProtocolServer(ServerSetting serverSetting) {
        super(serverSetting);
    }

    @Override
    protected void doStart() {
        log.info("IPC protocol server ready");
    }

    @Override
    protected void doStop() {
        log.info("IPC protocol server stopped");
    }

    @Override
    public String getListenAddress() {
        return "localhost";
    }

    @Override
    public int getListenPort() {
        return 0;
    }

    /**
     *        IPC       
     * <p>
     *     JS                 JSON                 {@link DefaultServletRequest}   
     *                                   JSON          
     * </p>
     *
     * @param clientId                
     * @param path                        /api/hello   
     * @param jsonBody JSON          
     * @return JSON                
     */
    public String handleMessage(String clientId, String path, String jsonBody) {
        try {
            DefaultServletRequest request = DefaultServletRequest.builder()
                    .requestId("ipc-" + System.currentTimeMillis())
                    .path(path)
                    .method("POST")
                    .body(jsonBody != null ? jsonBody.getBytes(StandardCharsets.UTF_8) : new byte[0])
                    .contentType("application/json")
                    .charset("UTF-8")
                    .build();

            ServletResponse response = createServletResponse();

            doHandle(request, response);

            String result = response.getBodyString();
            return result != null ? result : "{}";
        }
catch (Exception e) {
            log.error("IPC message handling failed: path={}", path, e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}