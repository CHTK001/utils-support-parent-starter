package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * OkHttp Handler — intercepts OkHttp call execution.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OkHttpHandler extends AbstractAppHandler {

    /**
     * CALL
     */
    private static final String CALL = "okhttp3/Call";
    /**
     * execute methods
     */
    private static final String[] EXECUTE_METHODS = {"execute", "enqueue"};

    @Override
    public String name() {
        return "okhttp-handler";
    }

    @Override
    protected String enabledKey() {
        return "okhttp.enabled";
    }

    @Override
    protected Software software() {
        return Software.OKHTTP;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(CALL, EXECUTE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.OKHTTP)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}