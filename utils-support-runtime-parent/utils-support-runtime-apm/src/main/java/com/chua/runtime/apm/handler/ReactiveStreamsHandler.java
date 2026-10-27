package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * 响应式 流 处理器 — intercepts 发布/Subscriber operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ReactiveStreamsHandler extends AbstractAppHandler {

    /**
     * PUBLISHER
     */
    private static final String PUBLISHER = "org/reactivestreams/Publisher";
    /**
     * SUBSCRIBER
     */
    private static final String SUBSCRIBER = "org/reactivestreams/Subscriber";
    /**
     * SUBSCRIPTION
     */
    private static final String SUBSCRIPTION = "org/reactivestreams/Subscription";
    /**
     * 订阅 方法
     */
    private static final String[] SUBSCRIBE_METHODS = {"subscribe"};
    /**
     * on 方法
     */
    private static final String[] ON_METHODS = {"onNext", "onError", "onComplete"};
    /**
     * 请求 方法
     */
    private static final String[] REQUEST_METHODS = {"request", "cancel"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "reactive-streams-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "reactive-streams.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.REACTIVE_STREAMS;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.MESSAGE;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(PUBLISHER, SUBSCRIBE_METHODS);
        registerAll(SUBSCRIBER, ON_METHODS);
        registerAll(SUBSCRIPTION, REQUEST_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.MESSAGE)
                .software(Software.REACTIVE_STREAMS)
                .host("reactive-streams")
                .port(0)
                .path("/")
                .build();
    }
}