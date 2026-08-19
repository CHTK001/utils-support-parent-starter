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

    /**
     * websocket 会话
     */
    private static final String WEBSOCKET_SESSION = "javax/websocket/Session";
    /**
     * 会话 methods
     */
    private static final String[] SESSION_METHODS = {"getBasicRemote", "getAsyncRemote", "close"};
    /**
     * remote endpoint
     */
    private static final String REMOTE_ENDPOINT = "javax/websocket/RemoteEndpoint";
    /**
     * remote methods
     */
    private static final String[] REMOTE_METHODS = {"sendText", "sendBinary", "sendObject"};

    @Override
    /** Name */
    public String name() {
        return "websocket-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "websocket.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.WEBSOCKET;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.WEBSOCKET;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(WEBSOCKET_SESSION, SESSION_METHODS);
        registerAll(REMOTE_ENDPOINT, REMOTE_METHODS);
    }

    @Override
    /** 构建Target */
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