package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * WebSocket Handler — intercepts WebSocket connections and messages.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WebSocketHandler extends AbstractAppHandler {

    private static final String WEBSOCKET_SESSION = "javax/websocket/Session";
    private static final String[] SESSION_METHODS = {"getBasicRemote", "getAsyncRemote", "close"};
    private static final String REMOTE_ENDPOINT = "javax/websocket/RemoteEndpoint";
    private static final String[] REMOTE_METHODS = {"sendText", "sendBinary", "sendObject"};

    @Override
    public String name() {
        return "websocket-handler";
    }

    @Override
    protected String enabledKey() {
        return "websocket.enabled";
    }

    @Override
    protected Software software() {
        return Software.WEBSOCKET;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.WEBSOCKET;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(WEBSOCKET_SESSION, SESSION_METHODS);
        registerAll(REMOTE_ENDPOINT, REMOTE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.WEBSOCKET)
                .software(Software.WEBSOCKET)
                .host("websocket")
                .port(80)
                .path("/")
                .build();
    }
}