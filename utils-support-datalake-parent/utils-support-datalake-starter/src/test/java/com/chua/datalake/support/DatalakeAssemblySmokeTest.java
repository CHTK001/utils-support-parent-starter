package com.chua.datalake.support;

import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;
import com.chua.datalake.support.executor.DatalakeExecutorManager;
import com.chua.datalake.support.manager.SinkManager;
import com.chua.datalake.support.manager.SubscriberManager;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.pipeline.DefaultPipelineManager;
import com.chua.datalake.support.server.DatalakeServer;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.chua.datalake.support.spi.sink.AccessSink;
import com.chua.datalake.support.spi.sink.DataSink;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据湖 服务端装配冒烟测试。
 *
 * <p>覆盖四处跨组件契约，任何一处偏离都会让数据静默丢失：</p>
 * <ol>
 *   <li>{@code DatalakeExecutorManager#getTopic} 与发布端话题取值必须一致，否则跨进程订阅落在无人发布的话题上</li>
 *   <li>订阅回调进入管线时信封必须带受理时间戳，否则订阅位点恒为 0</li>
 *   <li>{@code SinkManager} 的存储型筛选与启停隔离，以及执行器降级派发对运行期注册 Sink 的可见性</li>
 *   <li>{@code ExecutorManager#start/#stop} 必须把生命周期下发到执行器：未注入分发器时要自建通道，
 *       否则订阅侧只会留下一条告警并静默跳过</li>
 * </ol>
 *
 * <p>运行方式：{@code java com.chua.datalake.support.DatalakeAssemblySmokeTest}</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DatalakeAssemblySmokeTest {

    /**
     * 成功计数
     */
    private static int passCount = 0;
    /**
     * 失败计数
     */
    private static int failureCount = 0;

    /**
     * main。
     *
     * @param args 参数
     * @throws Exception 反射取私有话题方法时抛出
     */
    public static void main(String[] args) throws Exception {
        topicMatchesPublisher();
        subscribeStampsTimestamp();
        storeSinksExcludesAccessSink();
        startFailureIsIsolated();
        degradeDispatchSeesLateSink();
        lifecycleReachesExecutor();
        serverRunsWithoutApiSurface();

        System.out.println("========================================");
        System.out.println("DatalakeAssemblySmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * 管理器给出的话题必须与发布端实际使用的话题逐字相同
     *
     * @throws Exception 反射失败
     */
    private static void topicMatchesPublisher() throws Exception {
        DatalakeExecutorManager manager = new DatalakeExecutorManager();
        ReactorDataSyncExecutor executor = manager.getExecutor("any");
        Method buildTopic = executor.getClass().getSuperclass().getDeclaredMethod("buildTopic", String.class);
        buildTopic.setAccessible(true);

        for (String sinkId : List.of("sink-a", "sink-b")) {
            String fromManager = manager.getTopic(sinkId);
            String fromPublisher = (String) buildTopic.invoke(executor, sinkId);
            System.out.println("OBSERVE topic sinkId=" + sinkId + " manager=" + fromManager
                    + " publisher=" + fromPublisher);
            check(fromPublisher.equals(fromManager),
                    sinkId + " 的管理器话题与发布端一致（manager=" + fromManager + ", publisher=" + fromPublisher + "）");
        }
    }

    /**
     * Chronicle 订阅回调进入管线时必须补上受理时间戳
     */
    private static void subscribeStampsTimestamp() {
        DatalakeExecutorManager manager = new DatalakeExecutorManager();
        CapturingProvider provider = new CapturingProvider();
        manager.setDispatcherProvider(provider);
        CapturingEngine engine = new CapturingEngine();
        manager.setPipelineEngine(null, engine);

        manager.getExecutor("any").subscribe("sink-a", rows -> {
        });
        System.out.println("OBSERVE subscribeTopics=" + provider.definitions.size());
        check(provider.definitions.size() == 1, "订阅注册了 1 个分发定义（实际=" + provider.definitions.size() + "）");

        Map<String, Object> row = new HashMap<>();
        row.put("id", "R1");
        long begin = System.currentTimeMillis();
        provider.definitions.get(0).dispatch(Collections.singletonList(row));
        check(engine.envelopes.size() == 1, "订阅消息驱动了一次管线执行（实际=" + engine.envelopes.size() + "）");
        long stamped = engine.envelopes.get(0).getTimestamp();
        System.out.println("OBSERVE subscribeTimestamp=" + stamped);
        check(stamped >= begin, "信封时间戳为受理时刻而非 0（实际=" + stamped + "）");
    }

    /**
     * 访问型 Sink 即使带了数据源也不能进存储型列表
     */
    private static void storeSinksExcludesAccessSink() {
        Map<String, DataSink> registry = new LinkedHashMap<>();
        registry.put("store", new StoreSink("store"));
        registry.put("access", new AccessWithDataSourceSink("access"));
        registry.put("empty", new RecordingSink("empty"));
        SinkManager manager = new SinkManager(registry);

        List<DataSink> store = manager.storeSinks();
        System.out.println("OBSERVE storeSinks=" + types(store));
        check(store.size() == 1, "存储型 Sink 只有 1 个（实际=" + store.size() + " " + types(store) + "）");
        check(store.isEmpty() || "store".equals(store.get(0).type()), "留下的是真正的存储型 Sink");
    }

    /**
     * 单个 Sink 启动失败不得中断其余 Sink
     */
    private static void startFailureIsIsolated() {
        ThrowingSink boom = new ThrowingSink("boom");
        LifecycleSink survivor = new LifecycleSink("survivor");
        Map<String, DataSink> registry = new LinkedHashMap<>();
        registry.put("boom", boom);
        registry.put("survivor", survivor);
        SinkManager manager = new SinkManager(registry);

        boolean escaped = true;
        try {
            manager.start();
            escaped = false;
        } catch (Exception e) {
            System.out.println("OBSERVE startEscaped=" + e.getClass().getSimpleName());
        }
        check(!escaped, "启动异常被隔离在 SinkManager 内");
        check(survivor.started, "启动失败的 Sink 之后的 Sink 仍然启动");

        try {
            manager.stop();
        } catch (Exception e) {
            check(false, "停止异常同样不得外抛（实际抛出 " + e.getClass().getSimpleName() + "）");
        }
        check(survivor.stopped, "停机阶段每个 Sink 都有释放资源的机会");
    }

    /**
     * 降级派发要按当前注册表内容查找，注册顺序不影响命中
     */
    private static void degradeDispatchSeesLateSink() {
        Map<String, DataSink> live = new ConcurrentHashMap<>();
        DatalakeExecutorManager manager = new DatalakeExecutorManager();
        manager.setPipelineEngine(null, null, live);

        RecordingSink late = new RecordingSink("d1");
        live.put("d1", late);

        Map<String, Object> row = new HashMap<>();
        row.put("id", "DEG1");
        manager.getExecutor("any").publish("d1", Collections.singletonList(row));
        System.out.println("OBSERVE degradeReceived=" + late.received.size());
        check(late.received.size() == 1,
                "注入注册表之后新增的 Sink 仍能收到降级派发（实际=" + late.received.size() + "）");
    }

    /**
     * 管理器的启停必须真正下发到执行器，否则跨进程摄入静默失效
     *
     * @throws Exception 反射读取执行器分发器字段时抛出
     */
    private static void lifecycleReachesExecutor() throws Exception {
        DatalakeExecutorManager manager = new DatalakeExecutorManager();
        CapturingProvider provider = new CapturingProvider();
        manager.setDispatcherProvider(provider);

        manager.start();
        manager.start();
        System.out.println("OBSERVE providerStarts=" + provider.starts);
        check(provider.starts == 1, "start() 下发一次启动且重复调用幂等（实际=" + provider.starts + "）");
        manager.stop();
        System.out.println("OBSERVE providerCloses=" + provider.closes);
        check(provider.closes == 1, "stop() 释放执行器持有的分发器（实际=" + provider.closes + "）");

        DatalakeExecutorManager solo = new DatalakeExecutorManager();
        Field field = ReactorDataSyncExecutor.class.getDeclaredField("chronicleProvider");
        field.setAccessible(true);
        check(field.get(solo.getExecutor("any")) == null, "未启动前执行器没有分发器");
        solo.start();
        Object built = field.get(solo.getExecutor("any"));
        System.out.println("OBSERVE selfOwnedChannel=" + (built == null ? "null" : built.getClass().getSimpleName()));
        check(built != null, "start() 让执行器自建派发通道，订阅不再静默跳过");
        solo.stop();
    }

    /**
     * 宿主没有注入 API 服务端时，数据湖服务照旧起停且不监听任何端口
     */
    private static void serverRunsWithoutApiSurface() {
        LifecycleSink sink = new LifecycleSink("api-less");
        Map<String, DataSink> registry = new LinkedHashMap<>();
        registry.put("api-less", sink);
        DatalakeServer server = new DatalakeServer(new DefaultPipelineManager(),
                new CapturingEngine(), null, new SinkManager(registry),
                new SubscriberManager(), null, null);
        server.start();
        System.out.println("OBSERVE apiLessStart sinkStarted=" + sink.started);
        check(sink.started, "无 API 服务端时 Sink 仍被启动");
        server.stop();
        check(sink.stopped, "停止时 Sink 被释放");
    }

    /**
     * Sink 类型列表
     *
     * @param sinks 待描述 Sink
     * @return 类型字符串
     */
    private static String types(List<DataSink> sinks) {
        List<String> out = new ArrayList<>();
        for (DataSink sink : sinks) {
            out.add(sink.type());
        }
        return out.toString();
    }

    /**
     * 捕获订阅定义的分发器替身
     */
    private static class CapturingProvider implements DispatcherProvider {

        /**
         * 捕获到的订阅定义
         */
        private final List<DispatcherDefinition> definitions = new ArrayList<>();
        /**
         * 启动调用次数
         */
        private int starts;
        /**
         * 关闭调用次数
         */
        private int closes;

        @Override
        public void start() {
            starts++;
        }

        @Override
        public void publish(String topic, Object body) {
            // 本用例只关心订阅侧
        }

        @Override
        public void subscribe(DispatcherDefinition definition) {
            definitions.add(definition);
        }

        @Override
        public void unsubscribe(DispatcherDefinition definition) {
            // 无状态
        }

        @Override
        public void close() {
            closes++;
        }
    }

    /**
     * 捕获进入管线的信封
     */
    private static class CapturingEngine implements PipelineEngine {

        /**
         * 收到的信封
         */
        private final List<DataEnvelope> envelopes = new ArrayList<>();

        @Override
        public void execute(String pipelineId, DataEnvelope envelope) {
            envelopes.add(envelope);
        }
    }

    /**
     * 记录收到的信封的 Sink
     */
    private static class RecordingSink implements DataSink {

        /**
         * 收到的信封
         */
        private final List<DataEnvelope> received = new ArrayList<>();
        /**
         * 类型标识
         */
        private final String kind;

        RecordingSink(String kind) {
            this.kind = kind;
        }

        @Override
        public String type() {
            return kind;
        }

        @Override
        public void start() {
            // 无外部资源
        }

        @Override
        public void stop() {
            // 无外部资源
        }

        @Override
        public boolean write(DataEnvelope envelope, Map<String, Object> config) {
            received.add(envelope);
            return true;
        }

        @Override
        public EngineDataSource<?> getDataSource() {
            return null;
        }
    }

    /**
     * 存储型 Sink：非访问型但暴露数据源
     */
    private static class StoreSink extends RecordingSink {

        StoreSink(String kind) {
            super(kind);
        }

        @Override
        public EngineDataSource<?> getDataSource() {
            return (EngineDataSource<?>) Proxy.newProxyInstance(
                    DatalakeAssemblySmokeTest.class.getClassLoader(),
                    new Class<?>[]{EngineDataSource.class},
                    (proxy, method, callArgs) -> "toString".equals(method.getName()) ? "fakeDS" : null);
        }
    }

    /**
     * 访问型 Sink 却带了数据源：第三方实现的常见写法，必须仍被排除在存储型之外
     */
    private static class AccessWithDataSourceSink extends StoreSink implements AccessSink {

        AccessWithDataSourceSink(String kind) {
            super(kind);
        }
    }

    /**
     * 启动即抛异常的 Sink
     */
    private static class ThrowingSink extends RecordingSink {

        ThrowingSink(String kind) {
            super(kind);
        }

        @Override
        public void start() {
            throw new IllegalStateException("模拟数据源不可达");
        }

        @Override
        public void stop() {
            throw new IllegalStateException("模拟释放失败");
        }
    }

    /**
     * 记录启停是否被调用的 Sink
     */
    private static class LifecycleSink extends RecordingSink {

        /**
         * 是否已启动
         */
        private boolean started;
        /**
         * 是否已停止
         */
        private boolean stopped;

        LifecycleSink(String kind) {
            super(kind);
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    /**
     * 校验并计数
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
