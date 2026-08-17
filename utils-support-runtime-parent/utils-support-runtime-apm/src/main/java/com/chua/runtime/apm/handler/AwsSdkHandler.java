package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * AWS SDK Handler — intercepts AWS client operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AwsSdkHandler extends AbstractAppHandler {

    private static final String CLIENT = "software/amazon/awssdk/core/client/ClientExecution";
    private static final String[] EXEC_METHODS = {"execute"};

    @Override
    public String name() {
        return "aws-sdk-handler";
    }

    @Override
    protected String enabledKey() {
        return "aws-sdk.enabled";
    }

    @Override
    protected Software software() {
        return Software.AWS_SDK;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(CLIENT, EXEC_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.AWS_SDK)
                .host("aws")
                .port(443)
                .path("/")
                .build();
    }
}