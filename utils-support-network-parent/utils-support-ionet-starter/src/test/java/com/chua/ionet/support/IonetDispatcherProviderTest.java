package com.chua.ionet.support;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.Subscribe;
import com.chua.ionet.support.dispatcher.IonetDispatcherProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IonetDispatcherProvider（eventbus 分发器）测试。
 *
 * <p>验证基于 ionet 的事件总线：订阅定义注册后，publish 到主题
 * 能触发订阅方法的反射调用（{@code definition.dispatch(body)}）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class IonetDispatcherProviderTest {

    /**
     * 订阅者：@Subscribe 标记接收方法。
     */
    static class EventSubscriber {
        final AtomicInteger orderCount = new AtomicInteger();
        final CountDownLatch orderLatch = new CountDownLatch(1);
        final AtomicInteger userCount = new AtomicInteger();
        final CountDownLatch userLatch = new CountDownLatch(1);

        @Subscribe(topic = "order/created")
        public void onOrderCreated(String message) {
            orderCount.incrementAndGet();
            orderLatch.countDown();
        }

        @Subscribe(topic = "user/login")
        public void onUserLogin(String message) {
            userCount.incrementAndGet();
            userLatch.countDown();
        }
    }

    private IonetDispatcherProvider provider;
    private EventSubscriber subscriber;

    @BeforeEach
    void setUp() throws Exception {
        provider = new IonetDispatcherProvider(DispatcherConfig.builder().url("ionet://127.0.0.1:0").build());
        subscriber = new EventSubscriber();
        // 注册订阅定义
        register(subscriber, "onOrderCreated", "order/created");
        register(subscriber, "onUserLogin", "user/login");
        provider.start();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void publishDispatchesToSubscribedMethod() throws Exception {
        provider.publish("order/created", "order-1001");
        assertTrue(subscriber.orderLatch.await(5, TimeUnit.SECONDS), "order/created 订阅方法应被触发");
        assertEquals(1, subscriber.orderCount.get());

        provider.publish("user/login", "user-42");
        assertTrue(subscriber.userLatch.await(5, TimeUnit.SECONDS), "user/login 订阅方法应被触发");
        assertEquals(1, subscriber.userCount.get());
    }

    /**
     * 反射构造 DispatcherDefinition 并注册。
     */
    private void register(Object target, String methodName, String topic) throws Exception {
        Method method = target.getClass().getMethod(methodName, String.class);
        DispatcherDefinition definition = new DispatcherDefinition(target, method, List.of(topic));
        provider.subscribe(definition);
    }
}
