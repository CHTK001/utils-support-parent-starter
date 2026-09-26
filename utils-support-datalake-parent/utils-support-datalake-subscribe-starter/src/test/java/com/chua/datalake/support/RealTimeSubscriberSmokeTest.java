package com.chua.datalake.support;

import com.chua.common.support.concurrent.offset.OffsetFlow;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.subscriber.RealTimeDatalakeSubscriber;

import java.nio.file.Files;

/**
 * 实时订阅器位点语义冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.datalake.support.RealTimeSubscriberSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * <p>锁定三条语义：位点只统计真正投递成功的记录（至少一次）、冷流不订阅不推进位点、
 * 位点是自增序号而不是事件的 epoch 时间戳。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RealTimeSubscriberSmokeTest {

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
     * @param args 参数
     */
    public static void main(String[] args) throws Exception {
        OffsetFlow flow = OffsetFlow.create()
                .basePath(Files.createTempDirectory("sub-smoke").toString())
                .start();

        StringBuilder seen = new StringBuilder();
        RealTimeDatalakeSubscriber subscriber = new RealTimeDatalakeSubscriber(
                "sub-ok", flow, envelope -> seen.append(envelope.getParsed().get("id")));
        check(subscriber.currentOffset() == 0, "初始位点为 0");
        subscriber.push(envelope("A")).block();
        subscriber.push(envelope("B")).block();
        subscriber.push(envelope("C")).block();
        check("ABC".contentEquals(seen), "三条数据全部投递到消费方（seen=" + seen + "）");
        check(subscriber.currentOffset() == 3,
                "位点等于已投递条数（实际=" + subscriber.currentOffset() + "，若为 epoch 毫秒说明被 timestamp 污染）");
        check(subscriber.currentOffset() < 1_000_000L, "位点仍是序号量级");

        RealTimeDatalakeSubscriber cold = new RealTimeDatalakeSubscriber(
                "sub-cold", flow, envelope -> {
                });
        cold.push(envelope("X"));
        check(cold.currentOffset() == 0, "冷流未订阅不得推进位点");

        RealTimeDatalakeSubscriber failing = new RealTimeDatalakeSubscriber(
                "sub-fail", flow, envelope -> {
                    throw new IllegalStateException("消费方失败");
                });
        boolean surfaced = false;
        try {
            failing.push(envelope("Y")).block();
        } catch (Exception e) {
            surfaced = true;
        }
        check(surfaced, "消费方异常沿 Mono 错误信号上抛");
        check(failing.currentOffset() == 0, "消费失败时位点保持原位，该条可重投（至少一次）");

        System.out.println("========================================");
        System.out.println("RealTimeSubscriberSmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * 构造带 epoch 时间戳的信封
     *
     * @param id 数据标识
     * @return 信封
     */
    private static DataEnvelope envelope(String id) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", id);
        return DataEnvelope.builder()
                .parsed(row)
                .pipelineId("p1")
                .traceId("trace-" + id)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 校验并计数
     *
     * @param condition 条件
     * @param message 消息
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
