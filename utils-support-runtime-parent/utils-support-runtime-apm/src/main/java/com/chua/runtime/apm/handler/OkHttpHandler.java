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
    /** Name */
    public String name() {
        return "okhttp-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "okhttp.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.OKHTTP;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(CALL, EXECUTE_METHODS);
    }

    @Override
    /** 构建Target */
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