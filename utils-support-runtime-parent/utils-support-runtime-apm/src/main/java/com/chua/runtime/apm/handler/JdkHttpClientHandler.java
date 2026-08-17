package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * JDK HttpURLConnection Handler — intercepts HTTP requests via java.net.HttpURLConnection.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JdkHttpClientHandler extends AbstractAppHandler {

    private static final String HTTP_URL_CONNECTION = "java/net/HttpURLConnection";
    private static final String[] CONNECT_METHODS = {"connect", "getInputStream", "getOutputStream", "getResponseCode"};

    @Override
    public String name() {
        return "jdk-http-client-handler";
    }

    @Override
    protected String enabledKey() {
        return "jdk-http-client.enabled";
    }

    @Override
    protected Software software() {
        return Software.JDK_HTTP_CLIENT;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(HTTP_URL_CONNECTION, CONNECT_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.JDK_HTTP_CLIENT)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}