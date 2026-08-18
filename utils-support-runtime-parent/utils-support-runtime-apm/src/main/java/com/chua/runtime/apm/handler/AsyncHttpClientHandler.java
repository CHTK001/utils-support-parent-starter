package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * AsyncHttpClient Handler — intercepts async HTTP client requests.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AsyncHttpClientHandler extends AbstractAppHandler {

    /**
     * async HTTP 客户端
     */
    private static final String ASYNC_HTTP_CLIENT = "org/asynchttpclient/AsyncHttpClient";
    /**
     * execute methods
     */
    private static final String[] EXECUTE_METHODS = {"executeRequest", "execute"};

    @Override
    public String name() {
        return "async-http-client-handler";
    }

    @Override
    protected String enabledKey() {
        return "async-http-client.enabled";
    }

    @Override
    protected Software software() {
        return Software.ASYNC_HTTP_CLIENT;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(ASYNC_HTTP_CLIENT, EXECUTE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.ASYNC_HTTP_CLIENT)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}