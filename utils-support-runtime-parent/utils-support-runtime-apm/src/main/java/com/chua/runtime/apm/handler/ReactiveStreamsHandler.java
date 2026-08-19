package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Reactive Streams Handler — intercepts Publisher/Subscriber operations.
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
     * subscribe methods
     */
    private static final String[] SUBSCRIBE_METHODS = {"subscribe"};
    /**
     * on methods
     */
    private static final String[] ON_METHODS = {"onNext", "onError", "onComplete"};
    /**
     * 请求 methods
     */
    private static final String[] REQUEST_METHODS = {"request", "cancel"};

    @Override
    /** Name */
    public String name() {
        return "reactive-streams-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "reactive-streams.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.REACTIVE_STREAMS;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.MESSAGE;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(PUBLISHER, SUBSCRIBE_METHODS);
        registerAll(SUBSCRIBER, ON_METHODS);
        registerAll(SUBSCRIPTION, REQUEST_METHODS);
    }

    @Override
    /** 构建Target */
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