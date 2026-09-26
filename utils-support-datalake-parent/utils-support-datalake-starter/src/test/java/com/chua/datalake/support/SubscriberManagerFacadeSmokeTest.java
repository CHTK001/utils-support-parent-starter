package com.chua.datalake.support;

import com.chua.common.support.concurrent.offset.OffsetFlow;
import com.chua.datalake.support.manager.SubscriberManager;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.subscriber.RealTimeDatalakeSubscriber;
import com.chua.datalake.support.subscriber.SubscriberRegistry;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订阅管理器门面冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.datalake.support.SubscriberManagerFacadeSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * <p>锁定的语义：{@link SubscriberManager} 不另存订阅器列表，
 * 所有登记/查询/推送都落到进程级 {@link SubscriberRegistry}，
 * 因此应用侧经门面登记的订阅器与 Sink 侧经 SPI 通道看到的是同一份名册。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SubscriberManagerFacadeSmokeTest {

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
                .basePath(Files.createTempDirectory("mgr-smoke").toString())
                .start();
        try {
            SubscriberRegistry.getInstance().clear();
            SubscriberManager manager = new SubscriberManager();
            AtomicInteger hits = new AtomicInteger();
            RealTimeDatalakeSubscriber subscriber =
                    new RealTimeDatalakeSubscriber("mgr-sub", flow, env -> hits.incrementAndGet());

            manager.register(subscriber);
            System.out.println("OBSERVE facadeSize=" + manager.subscriberCount()
                    + " registrySize=" + SubscriberRegistry.getInstance().size());
            check(manager.subscriberCount() == 1 && SubscriberRegistry.getInstance().size() == 1,
                    "门面登记即注册表登记（count=" + manager.subscriberCount() + "）");
            check(manager.contains("mgr-sub"), "门面可查询订阅器在册");
            check(manager.subscribers().stream().anyMatch(s -> "mgr-sub".equals(s.subscriberId())),
                    "门面返回的快照含已登记订阅器");

            int delivered = manager.push(envelope("M1"));
            System.out.println("OBSERVE delivered=" + delivered + " hits=" + hits.get()
                    + " offset=" + subscriber.currentOffset());
            check(delivered == 1 && hits.get() == 1,
                    "门面 push 走同一条投递链（delivered=" + delivered + ", hits=" + hits.get() + "）");
            check(subscriber.currentOffset() == 1,
                    "经门面投递同样推进位点（offset=" + subscriber.currentOffset() + "）");

            manager.unregister("mgr-sub");
            check(!manager.contains("mgr-sub"), "门面 unregister 生效");

            manager.register(subscriber);
            manager.stop();
            System.out.println("OBSERVE afterStopSize=" + manager.subscriberCount()
                    + " afterStopRegistry=" + SubscriberRegistry.getInstance().size());
            check(manager.subscriberCount() == 0 && SubscriberRegistry.getInstance().size() == 0,
                    "stop() 清空注册表（count=" + manager.subscriberCount() + "）");
        } finally {
            SubscriberRegistry.getInstance().clear();
            flow.close();
        }

        System.out.println("========================================");
        System.out.println("SubscriberManagerFacadeSmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * 构造一条最小信封。
     *
     * @param id 业务主键
     * @return 信封
     */
    private static DataEnvelope envelope(String id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        return DataEnvelope.builder()
                .parsed(row)
                .pipelineId("p-mgr")
                .traceId("trace-" + id)
                .timestamp(System.currentTimeMillis())
                .build();
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
