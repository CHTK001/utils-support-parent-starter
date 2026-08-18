package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * JMS Handler — intercepts JMS producer/consumer operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JmsHandler extends AbstractAppHandler {

    /**
     * 消息 producer
     */
    private static final String MESSAGE_PRODUCER = "javax/jms/MessageProducer";
    /**
     * 消息 consumer
     */
    private static final String MESSAGE_CONSUMER = "javax/jms/MessageConsumer";
    /**
     * SESSION
     */
    private static final String SESSION = "javax/jms/Session";
    /**
     * producer methods
     */
    private static final String[] PRODUCER_METHODS = {"send"};
    /**
     * consumer methods
     */
    private static final String[] CONSUMER_METHODS = {"receive", "receiveNoWait"};
    /**
     * 会话 methods
     */
    private static final String[] SESSION_METHODS = {"createProducer", "createConsumer", "createDurableConsumer"};

    @Override
    public String name() {
        return "jms-handler";
    }

    @Override
    protected String enabledKey() {
        return "jms.enabled";
    }

    @Override
    protected Software software() {
        return Software.JMS_CLIENT;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.JMS;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(MESSAGE_PRODUCER, PRODUCER_METHODS);
        registerAll(MESSAGE_CONSUMER, CONSUMER_METHODS);
        registerAll(SESSION, SESSION_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.JMS)
                .software(Software.JMS_CLIENT)
                .host("jms")
                .port(Protocol.JMS.defaultPort())
                .path("/")
                .build();
    }
}