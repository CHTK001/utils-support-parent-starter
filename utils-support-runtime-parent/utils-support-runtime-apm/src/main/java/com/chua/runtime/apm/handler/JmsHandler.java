package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * JMS 处理器 — intercepts JMS producer/consumer operations.
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
     * 会话
     */
    private static final String SESSION = "javax/jms/Session";
    /**
     * producer 方法
     */
    private static final String[] PRODUCER_METHODS = {"send"};
    /**
     * consumer 方法
     */
    private static final String[] CONSUMER_METHODS = {"receive", "receiveNoWait"};
    /**
     * 会话 方法
     */
    private static final String[] SESSION_METHODS = {"createProducer", "createConsumer", "createDurableConsumer"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "jms-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "jms.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.JMS_CLIENT;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.JMS;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(MESSAGE_PRODUCER, PRODUCER_METHODS);
        registerAll(MESSAGE_CONSUMER, CONSUMER_METHODS);
        registerAll(SESSION, SESSION_METHODS);
    }

    /**
     * 构建Target
    */
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