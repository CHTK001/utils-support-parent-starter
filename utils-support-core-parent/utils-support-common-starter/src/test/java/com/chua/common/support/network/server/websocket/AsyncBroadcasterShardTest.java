// [P3C A20 豁免] A20/A30/A31: 测试断言与并发参数含魔法值场景，豁免。
package com.chua.common.support.network.server.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 广播扇出器的分片注册与定向投递回归。
 *
 * <p>两条护栏，都是为了百万连接规模：</p>
 * <ol>
 *   <li><b>每帧只访问每条连接一次</b>：上一版每片 worker 都遍历全量连接再按哈希丢弃，
 *       代价是 {@code n × 分片数}。注册时归档后应回到 {@code n}。</li>
 *   <li><b>定向投递不遍历连接</b>：私信走分片队列直达目标，代价 O(1)。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.52
 */
class AsyncBroadcasterShardTest {

    /**
     * 只记录收到帧首字节的假连接。
     */
    private static final class FakeSink implements WsSink {

        /**
         * 收到的帧（只留首字节，足以判顺序与数量）。
         */
        private final List<Byte> received = new CopyOnWriteArrayList<>();

        @Override
        public void writeFrame(byte[] frame) {
            received.add(frame[0]);
        }

        /**
         * 收到帧数。
         *
         * @return 帧数
         */
        int count() {
            return received.size();
        }

        /**
         * 收到的帧序列。
         *
         * @return 帧首字节序列
         */
        List<Byte> frames() {
            return received;
        }
    }

    /**
     * 轮询等待条件成立。
     *
     * @param condition  条件
     * @param timeoutMs  超时（毫秒）
     * @param what       失败时的说明
     * @throws InterruptedException 被中断
     */
    private static void await(BooleanSupplier condition, long timeoutMs, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20L);
        }
        assertTrue(condition.getAsBoolean(), "超时未满足：" + what);
    }

    /**
     * 广播时每帧只应访问每条连接一次。
     *
     * @throws Exception 被中断
     */
    @Test
    @Timeout(60)
    @DisplayName("广播每帧只访问每条连接一次（n 而不是 n×分片）")
    void broadcastVisitsEachConnectionOnce() throws Exception {
        int shards = 4;
        int n = 200;
        AsyncBroadcaster<FakeSink> broadcaster = new AsyncBroadcaster<>(shards, 256);
        List<FakeSink> sinks = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                FakeSink sink = new FakeSink();
                sinks.add(sink);
                broadcaster.register(sink);
            }
            assertEquals(n, broadcaster.snapshot().size(), "注册的连接数不符");

            broadcaster.publish(new byte[]{1});
            await(() -> sinks.stream().allMatch(s -> s.count() >= 1), 5000L, "全部连接收到 1 帧");

            // 旧实现每片都遍历全量再过滤 → n × shards = 800，本断言会变红
            assertEquals(n, broadcaster.getVisitedLinks(),
                    "访问连接次数应为连接数 " + n + "；若为 " + (n * shards)
                            + " 说明退化回了「遍历全量再按哈希过滤」");
        } finally {
            broadcaster.close();
        }
    }

    /**
     * 单连接内帧顺序必须与发布顺序一致（分片不能打乱同连接的帧序）。
     *
     * @throws Exception 被中断
     */
    @Test
    @Timeout(60)
    @DisplayName("单连接内帧顺序与发布顺序一致")
    void preservesPerConnectionOrder() throws Exception {
        AsyncBroadcaster<FakeSink> broadcaster = new AsyncBroadcaster<>(4, 256);
        List<FakeSink> sinks = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                FakeSink sink = new FakeSink();
                sinks.add(sink);
                broadcaster.register(sink);
            }
            int frames = 30;
            for (int i = 0; i < frames; i++) {
                broadcaster.publish(new byte[]{(byte) i});
            }
            await(() -> sinks.stream().allMatch(s -> s.count() >= frames), 5000L, "全部连接收到 30 帧");

            for (FakeSink sink : sinks) {
                for (int i = 0; i < frames; i++) {
                    assertEquals((byte) i, sink.frames().get(i).byteValue(), "帧顺序被打乱");
                }
            }
        } finally {
            broadcaster.close();
        }
    }

    /**
     * 定向投递只到达目标连接，且不遍历连接集合。
     *
     * @throws Exception 被中断
     */
    @Test
    @Timeout(60)
    @DisplayName("定向投递只到达目标连接")
    void sendToDeliversOnlyToTarget() throws Exception {
        AsyncBroadcaster<FakeSink> broadcaster = new AsyncBroadcaster<>(4, 256);
        List<FakeSink> sinks = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                FakeSink sink = new FakeSink();
                sinks.add(sink);
                broadcaster.register(sink);
            }

            broadcaster.sendTo(sinks.get(1), new byte[]{9});
            await(() -> sinks.get(1).count() >= 1, 3000L, "目标连接收到定向帧");
            Thread.sleep(200L);

            assertEquals(1, sinks.get(1).count(), "目标连接应收到 1 帧");
            assertEquals(0, sinks.get(0).count(), "非目标连接不应收到定向帧");
            assertEquals(0, sinks.get(2).count(), "非目标连接不应收到定向帧");
            assertEquals(0L, broadcaster.getVisitedLinks(), "定向投递不应遍历连接集合");
        } finally {
            broadcaster.close();
        }
    }

    /**
     * 注销后的连接不再参与广播。
     *
     * @throws Exception 被中断
     */
    @Test
    @Timeout(60)
    @DisplayName("注销后不再参与广播")
    void unregisterStopsDelivery() throws Exception {
        AsyncBroadcaster<FakeSink> broadcaster = new AsyncBroadcaster<>(4, 256);
        FakeSink keep = new FakeSink();
        FakeSink gone = new FakeSink();
        try {
            broadcaster.register(keep);
            broadcaster.register(gone);
            broadcaster.unregister(gone);
            assertEquals(1, broadcaster.snapshot().size(), "注销后快照应只剩 1 条");

            broadcaster.publish(new byte[]{1});
            await(() -> keep.count() >= 1, 3000L, "未注销连接收到帧");
            Thread.sleep(200L);

            assertEquals(0, gone.count(), "已注销连接不应再收到广播");
        } finally {
            broadcaster.close();
        }
    }
}
