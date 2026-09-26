package com.chua.datalake.support;

import com.chua.common.support.concurrent.offset.OffsetFlow;
import com.chua.datalake.support.engine.DefaultPipelineEngine;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.model.PipelineState;
import com.chua.datalake.support.pipeline.DefaultPipelineManager;
import com.chua.datalake.support.sink.RealTimeSink;
import com.chua.datalake.support.spi.pipeline.PipelineManager;
import com.chua.datalake.support.spi.sink.DataSink;
import com.chua.datalake.support.subscriber.RealTimeDatalakeSubscriber;
import com.chua.datalake.support.subscriber.Subscriber;
import com.chua.datalake.support.subscriber.SubscriberRegistry;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时订阅链冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.datalake.support.SubscriberChainSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * <p>锁定的链路语义：管线执行引擎 → {@code RealTimeSink} → SPI 通道
 * {@code SubscriberChannel} → {@link SubscriberRegistry} → 订阅器。
 * 每段都要求可观测的证据：谁收到、收到几次、位点推进到哪、失败时状态是什么。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SubscriberChainSmokeTest {

    /**
     * 只挂实时 Sink 的管线 DSL
     */
    private static final String DSL_REALTIME =
            "{\"id\":\"p-chain\",\"stages\":{\"default\":{\"sink\":[{\"type\":\"realtime\"}]}}}";

    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 成功计数
     */
    private static int passCount = 0;

    /**
     * main。
     *
     * @param args 参数
     * @throws Exception 创建临时目录失败
     */
    public static void main(String[] args) throws Exception {
        OffsetFlow flow = OffsetFlow.create()
                .basePath(Files.createTempDirectory("chain-smoke").toString())
                .start();
        try {
            noSubscriberRegistered(flow);
            engineReachesSubscriber(flow);
            topicRoutesDelivery(flow);
            failingSubscriberIsIsolated(flow);
            channelDeliversExactlyOnce(flow);
            batchPushStaysOrdered(flow);
        } finally {
            SubscriberRegistry.getInstance().clear();
            flow.close();
        }

        System.out.println("========================================");
        System.out.println("SubscriberChainSmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * 场景1：没有任何订阅器在册时，实时 Sink 必须判失败而不是假成功。
     *
     * @param flow 位点门面
     */
    private static void noSubscriberRegistered(OffsetFlow flow) {
        SubscriberRegistry.getInstance().clear();
        DataEnvelope envelope = envelope("N1", "t-none", null);
        DefaultPipelineEngine engine = engine();
        engine.execute("p-chain", envelope);
        System.out.println("OBSERVE case1 state=" + envelope.getState()
                + " trace=" + envelope.getTrace());
        check(envelope.getState() == PipelineState.SINK_FAIL,
                "无订阅器时状态为 SINK_FAIL（实际=" + envelope.getState() + "）");
        check(String.valueOf(envelope.getTrace()).contains("write=false"),
                "trace 记录 write=false 而非异常（实际=" + envelope.getTrace() + "）");
    }

    /**
     * 场景2：管线执行后数据要真的到达订阅器，且位点推进。
     *
     * @param flow 位点门面
     */
    private static void engineReachesSubscriber(OffsetFlow flow) {
        SubscriberRegistry.getInstance().clear();
        StringBuilder seen = new StringBuilder();
        RealTimeDatalakeSubscriber subscriber =
                new RealTimeDatalakeSubscriber("chain-basic", flow, env -> seen.append(env.getParsed().get("id")));
        subscriber.subscribe();
        check(SubscriberRegistry.getInstance().contains("chain-basic"), "subscribe() 把订阅器登记进注册表");

        DataEnvelope envelope = envelope("E1", "t-basic", null);
        engine().execute("p-chain", envelope);
        System.out.println("OBSERVE case2 seen=" + seen + " offset=" + subscriber.currentOffset()
                + " state=" + envelope.getState());
        check("E1".contentEquals(seen), "数据经 Sink→通道→注册表到达订阅器（seen=" + seen + "）");
        check(subscriber.currentOffset() == 1,
                "订阅器位点推进到 1（实际=" + subscriber.currentOffset() + "）");
        check(envelope.getState() == PipelineState.SINK_OK, "状态为 SINK_OK（实际=" + envelope.getState() + "）");
    }

    /**
     * 场景3：按主题路由，不相关的订阅器不得收到数据。
     *
     * @param flow 位点门面
     */
    private static void topicRoutesDelivery(OffsetFlow flow) {
        SubscriberRegistry.getInstance().clear();
        AtomicInteger orders = new AtomicInteger();
        AtomicInteger users = new AtomicInteger();
        register(new RealTimeDatalakeSubscriber("chain-orders", flow, env -> orders.incrementAndGet(), set("orders")));
        register(new RealTimeDatalakeSubscriber("chain-users", flow, env -> users.incrementAndGet(), set("users")));

        engine().execute("p-chain", envelope("T1", "t-order-only", set("orders")));
        System.out.println("OBSERVE case3a orders=" + orders.get() + " users=" + users.get());
        check(orders.get() == 1 && users.get() == 0,
                "orders 主题只命中 orders 订阅器（orders=" + orders.get() + ", users=" + users.get() + "）");

        DataEnvelope topicless = envelope("T2", "t-order-less", null);
        engine().execute("p-chain", topicless);
        System.out.println("OBSERVE case3b orders=" + orders.get() + " users=" + users.get()
                + " state=" + topicless.getState());
        check(orders.get() == 1 && users.get() == 0,
                "无主题信封不命中任何声明了主题的订阅器（orders=" + orders.get() + ", users=" + users.get() + "）");
        check(topicless.getState() == PipelineState.SINK_FAIL,
                "无人认领的无主题信封记 SINK_FAIL（实际=" + topicless.getState() + "）");

        SubscriberRegistry.getInstance().clear();
        AtomicInteger all = new AtomicInteger();
        register(new RealTimeDatalakeSubscriber("chain-all", flow, env -> all.incrementAndGet()));
        engine().execute("p-chain", envelope("T3", "t-any", set("anything")));
        System.out.println("OBSERVE case3c all=" + all.get());
        check(all.get() == 1, "未声明主题的订阅器全量接收（all=" + all.get() + "）");
    }

    /**
     * 场景4：单个订阅器抛错只影响它自己，不阻断其他订阅器。
     *
     * @param flow 位点门面
     */
    private static void failingSubscriberIsIsolated(OffsetFlow flow) {
        SubscriberRegistry.getInstance().clear();
        AtomicInteger healthy = new AtomicInteger();
        register(new RealTimeDatalakeSubscriber("chain-bad", flow, env -> {
            throw new IllegalStateException("消费方失败");
        }));
        register(new RealTimeDatalakeSubscriber("chain-good", flow, env -> healthy.incrementAndGet()));

        DataEnvelope envelope = envelope("F1", "t-fail", null);
        engine().execute("p-chain", envelope);
        System.out.println("OBSERVE case4 healthy=" + healthy.get() + " state=" + envelope.getState()
                + " badOffset=0");
        check(healthy.get() == 1, "异常订阅器未阻断正常订阅器（healthy=" + healthy.get() + "）");
        check(envelope.getState() == PipelineState.SINK_OK, "至少一个下游收下即为 SINK_OK");
        check(offsetOf("chain-bad", flow) == 0 && offsetOf("chain-good", flow) == 1,
                "失败订阅器位点不推进、成功者推进（bad=" + offsetOf("chain-bad", flow)
                        + ", good=" + offsetOf("chain-good", flow) + "）");
    }

    /**
     * 场景5：SPI 通道去重，一条数据只投递一次。
     *
     * <p>同一实现可能因"文件登记名 + 注解别名"被解析两次，届时订阅器会收到两遍。</p>
     *
     * @param flow 位点门面
     */
    private static void channelDeliversExactlyOnce(OffsetFlow flow) {
        SubscriberRegistry.getInstance().clear();
        AtomicInteger hits = new AtomicInteger();
        register(new RealTimeDatalakeSubscriber("chain-dup", flow, env -> hits.incrementAndGet()));

        RealTimeSink sink = new RealTimeSink();
        sink.start();
        sink.write(envelope("D1", "t-dup", null), Collections.emptyMap());
        sink.write(envelope("D2", "t-dup", null), Collections.emptyMap());
        System.out.println("OBSERVE case5 hits=" + hits.get());
        check(hits.get() == 2, "两条数据各投递一次，通道未因别名重复展开（hits=" + hits.get() + "）");
    }

    /**
     * 场景6：批量推送串行，位点不会越过未消费的数据。
     *
     * <p>首条故意慢消费：{@code flatMap} 会让后两条先落地（顺序变 BCA），
     * {@code concatMap} 才能保持声明顺序。</p>
     *
     * @param flow 位点门面
     */
    private static void batchPushStaysOrdered(OffsetFlow flow) {
        SubscriberRegistry.getInstance().clear();
        StringBuilder seen = new StringBuilder();
        RealTimeDatalakeSubscriber subscriber = new RealTimeDatalakeSubscriber("chain-batch", flow, env -> {
            String id = String.valueOf(env.getParsed().get("id"));
            if ("A".equals(id)) {
                sleep(150);
            }
            seen.append(id);
        });
        subscriber.pushBatch(Flux.just(envelope("A", "t-b", null),
                envelope("B", "t-b", null),
                envelope("C", "t-b", null))).block();
        System.out.println("OBSERVE case6 seen=" + seen + " offset=" + subscriber.currentOffset());
        check("ABC".contentEquals(seen), "批量推送保持顺序（实际=" + seen + "）");
        check(subscriber.currentOffset() == 3,
                "位点等于已消费条数（实际=" + subscriber.currentOffset() + "）");
    }

    /**
     * 构造只挂 realtime Sink 的执行引擎。
     *
     * @return 执行引擎
     */
    private static DefaultPipelineEngine engine() {
        Map<String, DataSink> registry = new HashMap<>();
        registry.put("realtime", new RealTimeSink());
        PipelineManager pipelineManager = new DefaultPipelineManager();
        pipelineManager.savePipeline("p-chain", DSL_REALTIME);
        return new DefaultPipelineEngine(pipelineManager, registry, null);
    }

    /**
     * 登记订阅器。
     *
     * @param subscriber 订阅器
     */
    private static void register(Subscriber subscriber) {
        SubscriberRegistry.getInstance().register(subscriber);
    }

    /**
     * 查询订阅器位点。
     *
     * @param subscriberId 订阅器标识
     * @param flow         位点门面
     * @return 位点
     */
    private static long offsetOf(String subscriberId, OffsetFlow flow) {
        return flow.current(subscriberId);
    }

    /**
     * 构造信封。
     *
     * @param id     业务主键
     * @param trace  追踪标识
     * @param topics 主题集合，空表示无主题
     * @return 信封
     */
    private static DataEnvelope envelope(String id, String trace, Set<String> topics) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        return DataEnvelope.builder()
                .parsed(row)
                .pipelineId("p-chain")
                .traceId(trace)
                .topics(topics)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 构造单元素集合的可变副本。
     *
     * @param topic 主题
     * @return 主题集合
     */
    private static Set<String> set(String topic) {
        Set<String> topics = new LinkedHashSet<>();
        topics.add(topic);
        return topics;
    }

    /**
     * 睡眠。
     *
     * @param millis 毫秒
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 校验并计数。
     *
     * @param condition 条件
     * @param message   消息
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("[PASS] " + message);
        } else {
            failureCount++;
            System.out.println("[FAIL] " + message);
        }
    }
}
