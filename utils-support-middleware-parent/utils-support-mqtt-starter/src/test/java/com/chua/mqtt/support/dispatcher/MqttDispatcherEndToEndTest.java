package com.chua.mqtt.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.mqtt.support.server.MqttServer;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MQTT 分发器端到端冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.mqtt.support.dispatcher.MqttDispatcherEndToEndTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * <p>链路为进程内闭环：Paho 客户端 ——&gt; 内嵌 {@link MqttServer} ——&gt; Paho 回调 ——&gt;
 * {@link DispatcherDefinition} 反射投递，全部走 127.0.0.1 随机端口，不访问外部 Broker。
 * 校验订阅过滤器（{@code #}/{@code +}）在分发器侧真正参与匹配，以及未启动/缺配置快速失败。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MqttDispatcherEndToEndTest {

    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 通过计数
     */
    private static int passCount = 0;

    /**
     * main。
     *
     * @param args 参数
     * @throws Exception 启动失败
     */
    public static void main(String[] args) throws Exception {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(0);
        setting.setShutdownQuietPeriod(0);
        MqttServer broker = new MqttServer(setting);
        broker.start();

        String url = "tcp://127.0.0.1:" + broker.getPort();
        DispatcherConfig config = DispatcherConfig.builder().url(url).clientId("smoke-consumer").build();
        // 服务端不会把消息回投给发起方，因此发布必须走另一个客户端，才能覆盖完整往返链路
        DispatcherConfig publisherConfig = DispatcherConfig.builder().url(url).clientId("smoke-publisher").build();
        MqttDispatcherProvider provider = new MqttDispatcherProvider(config);
        MqttDispatcherProvider publisher = new MqttDispatcherProvider(publisherConfig);
        provider.start();
        publisher.start();
        try {
            multiLevelWildcardDispatches(publisher, provider);
            exactTopicDispatches(publisher, provider);
            singleLevelWildcardIsScoped(publisher, provider);
            brokerInitiatedPublishDispatches(provider, broker);
            unsubscribeStopsDelivery(publisher, provider);
        } finally {
            publisher.close();
            provider.close();
            broker.stop();
        }
        publishBeforeStartFailsFast(config);
        missingUrlFailsFast();

        System.out.println("pass=" + passCount + ", fail=" + failureCount);
        if (failureCount > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    /**
     * 多级通配符过滤器必须命中其下任意主题。
     *
     * @param publisher 发布端
     * @param consumer  消费端
     * @throws Exception 反射失败
     */
    private static void multiLevelWildcardDispatches(MqttDispatcherProvider publisher,
                                                     MqttDispatcherProvider consumer) throws Exception {
        Recorder recorder = new Recorder();
        consumer.subscribe(definition(recorder, "evt/#"));
        publisher.publish("evt/created", "level-1");
        check("跨客户端发布可到达", recorder.await(1));
        publisher.publish("evt/a/b", "level-2");
        check("# 命中多层子主题", recorder.await(2));
        check("载荷原样投递", "level-2".equals(recorder.last()));
    }

    /**
     * 精确主题仍然要投递。
     *
     * @param publisher 发布端
     * @param consumer  消费端
     * @throws Exception 反射失败
     */
    private static void exactTopicDispatches(MqttDispatcherProvider publisher,
                                             MqttDispatcherProvider consumer) throws Exception {
        Recorder recorder = new Recorder();
        consumer.subscribe(definition(recorder, "plain/topic"));
        publisher.publish("plain/topic", "exact");
        check("精确主题被投递", recorder.await(1));
    }

    /**
     * 单层通配符只匹配一层，不得越层命中。
     *
     * @param publisher 发布端
     * @param consumer  消费端
     * @throws Exception 反射失败
     */
    private static void singleLevelWildcardIsScoped(MqttDispatcherProvider publisher,
                                                   MqttDispatcherProvider consumer) throws Exception {
        Recorder recorder = new Recorder();
        consumer.subscribe(definition(recorder, "sensor/+/temp"));
        publisher.publish("sensor/room1/humidity", "nope");
        publisher.publish("sensor/room1/temp", "yes");
        check("+ 命中的主题被投递", recorder.await(1));
        check("+ 不越层匹配", "yes".equals(recorder.last()));
        publisher.publish("sensor/a/b/temp", "nope2");
        publisher.publish("sensor/room2/temp", "yes2");
        check("+ 仅匹配单层", recorder.await(2) && "yes2".equals(recorder.last()));
    }

    /**
     * 服务端主动发布同样要经分发器投递。
     *
     * @param provider 分发器
     * @param broker   内嵌服务端
     * @throws Exception 反射失败
     */
    private static void brokerInitiatedPublishDispatches(MqttDispatcherProvider provider, MqttServer broker)
            throws Exception {
        Recorder recorder = new Recorder();
        provider.subscribe(definition(recorder, "srv/push"));
        broker.publish("srv/push", "from-broker");
        check("服务端发布经分发器投递", recorder.await(1) && "from-broker".equals(recorder.last()));
    }

    /**
     * 取消订阅后不再投递。
     *
     * @param publisher 发布端
     * @param consumer  消费端
     * @throws Exception 反射失败
     */
    private static void unsubscribeStopsDelivery(MqttDispatcherProvider publisher, MqttDispatcherProvider consumer)
            throws Exception {
        Recorder recorder = new Recorder();
        DispatcherDefinition definition = definition(recorder, "gone/#");
        consumer.subscribe(definition);
        publisher.publish("gone/1", "before");
        check("取消前可投递", recorder.await(1));
        consumer.unsubscribe(definition);
        publisher.publish("gone/2", "after");
        check("取消后不再投递", !recorder.await(2));
    }

    /**
     * 未启动即发布必须快速失败并给出可读原因。
     *
     * @param config 已含 Broker 地址的配置
     */
    private static void publishBeforeStartFailsFast(DispatcherConfig config) {
        MqttDispatcherProvider provider = new MqttDispatcherProvider(config);
        try {
            provider.publish("any", "body");
            check("未启动即发布被拒绝", false);
        } catch (IllegalStateException e) {
            System.out.println("OBSERVE publishBeforeStart=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            check("未启动即发布被拒绝", true);
        } catch (RuntimeException e) {
            System.out.println("OBSERVE publishBeforeStart=" + e.getClass().getName() + ": " + e.getMessage());
            check("未启动即发布被拒绝", false);
        }
    }

    /**
     * 缺少 Broker 地址启动必须快速失败。
     */
    private static void missingUrlFailsFast() {
        MqttDispatcherProvider provider = new MqttDispatcherProvider(DispatcherConfig.builder().build());
        try {
            provider.start();
            check("缺少 url 启动被拒绝", false);
        } catch (IllegalStateException e) {
            System.out.println("OBSERVE missingUrl=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            check("缺少 url 启动被拒绝", true);
        } catch (RuntimeException e) {
            System.out.println("OBSERVE missingUrl=" + e.getClass().getName() + ": " + e.getMessage());
            check("缺少 url 启动被拒绝", false);
        }
    }

    /**
     * 构造分发定义。
     *
     * @param recorder 记录器
     * @param topic    主题过滤器
     * @return 分发定义
     * @throws NoSuchMethodException 找不到订阅方法
     */
    private static DispatcherDefinition definition(Recorder recorder, String topic) throws NoSuchMethodException {
        Method method = Recorder.class.getMethod("onMessage", String.class);
        return new DispatcherDefinition(recorder, method, List.of(topic));
    }

    /**
     * 消息记录器。
     */
    public static class Recorder {

        /**
         * 已收到的载荷
         */
        private final List<String> received = new CopyOnWriteArrayList<>();

        /**
         * 订阅方法。
         *
         * @param payload 载荷
         */
        public void onMessage(String payload) {
            received.add(payload);
        }

        /**
         * @return 最后一条载荷
         */
        String last() {
            return received.isEmpty() ? null : received.get(received.size() - 1);
        }

        /**
         * 等待累计收到指定条数。
         *
         * @param count 期望条数
         * @return 达到条数返回 true
         * @throws InterruptedException 中断
         */
        boolean await(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                if (received.size() >= count) {
                    return true;
                }
                Thread.sleep(20);
            }
            return received.size() >= count;
        }
    }

    /**
     * 校验项。
     *
     * @param name   名称
     * @param passed 是否通过
     */
    private static void check(String name, boolean passed) {
        if (passed) {
            passCount++;
            System.out.println("  ok   " + name);
        } else {
            failureCount++;
            System.out.println("  FAIL " + name);
        }
    }
}
