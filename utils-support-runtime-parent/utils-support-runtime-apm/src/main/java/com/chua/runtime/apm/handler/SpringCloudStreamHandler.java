package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spring Cloud Stream Handler — intercepts message binding and sending.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SpringCloudStreamHandler extends AbstractAppHandler {

    /**
     * 消息 channel
     */
    private static final String MESSAGE_CHANNEL = "org/springframework/messaging/MessageChannel";
    /**
     * send methods
     */
    private static final String[] SEND_METHODS = {"send"};

    @Override
    public String name() {
        return "spring-cloud-stream-handler";
    }

    @Override
    protected String enabledKey() {
        return "spring-cloud-stream.enabled";
    }

    @Override
    protected Software software() {
        return Software.SPRING_CLOUD_STREAM;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.MESSAGE;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(MESSAGE_CHANNEL, SEND_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.MESSAGE)
                .software(Software.SPRING_CLOUD_STREAM)
                .host("spring-cloud-stream")
                .port(0)
                .path("/")
                .build();
    }
}