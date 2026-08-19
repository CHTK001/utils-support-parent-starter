package com.chua.common.support.shmqueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShmQueue 单元测试（抽象 API 层）。
 *
 * <p>本测试针对 common-starter 的抽象 ShmQueue 接口，要求 classpath 中至少存在一个
 * {@link ShmQueueProvider} SPI 实现（通常由 utils-support-native-shm-queue 模块提供）。
 * 如果没有任何实现，所有测试会被 JUnit 自动跳过。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@EnabledIf("isProviderAvailable")
class ShmQueueTest {

    /**
     * JUnit EnabledIf 回调：判断 classpath 中是否有 ShmQueueProvider 实现。
     *
     * @return true 表示至少有一个 Provider
     */
    static boolean isProviderAvailable() {
        return ServiceLoader.load(ShmQueueProvider.class).iterator().hasNext();
    }

    /**
     * 测试创建 + 单条 send/recv
     */
    @Test
    void testCreateAndSendRecv() {
        String name = uniqueName();
        try (ShmQueue q = ShmQueue.create(name, 16, 128, ShmQueue.Mode.HYBRID)) {
            byte[] data = "hello".getBytes();
            q.send(7, data);
            ShmQueue.Message msg = q.recv();
            assertEquals(7, msg.type());
            assertArrayEquals(data, msg.bytes());
        }
    }

    /**
     * 测试顺序：200 条消息不丢不乱
     */
    @Test
    void testOrderPreserved() {
        String name = uniqueName();
        try (ShmQueue q = ShmQueue.create(name, 64, 64, ShmQueue.Mode.SPIN)) {
            int count = 200;
            for (int i = 0; i < count; i++) {
                q.send(0, intToBytes(i));
            }
            for (int i = 0; i < count; i++) {
                ShmQueue.Message msg = q.recv();
                assertEquals(i, bytesToInt(msg.bytes()));
            }
        }
    }

    /**
     * 测试队列满时返回错误码
     */
    @Test
    void testQueueFullThrows() {
        String name = uniqueName();
        try (ShmQueue q = ShmQueue.create(name, 4, 64, ShmQueue.Mode.SPIN)) {
            q.send(1, new byte[]{1});
            q.send(1, new byte[]{2});
            q.send(1, new byte[]{3});
            ShmQueueException ex = assertThrows(ShmQueueException.class,
                    () -> q.send(1, new byte[]{4}));
            assertEquals(ShmQueue.ERR_QUEUE_FULL, ex.getCode());
        }
    }

    /**
     * 测试数据过大
     */
    @Test
    void testDataTooLargeThrows() {
        String name = uniqueName();
        try (ShmQueue q = ShmQueue.create(name, 8, 16, ShmQueue.Mode.HYBRID)) {
            byte[] big = new byte[64];
            ShmQueueException ex = assertThrows(ShmQueueException.class,
                    () -> q.send(1, big));
            assertEquals(ShmQueue.ERR_DATA_TOO_LARGE, ex.getCode());
        }
    }

    /**
     * 测试 recv 超时
     */
    @Test
    void testRecvTimeoutThrows() {
        String name = uniqueName();
        try (ShmQueue q = ShmQueue.create(name, 8, 64, ShmQueue.Mode.BLOCK)) {
            long t0 = System.nanoTime();
            ShmQueueException ex = assertThrows(ShmQueueException.class,
                    () -> q.recvTimeout(50_000_000L));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertEquals(ShmQueue.ERR_TIMEOUT, ex.getCode());
            assertTrue(elapsedMs >= 40 && elapsedMs < 2000,
                    "elapsed=" + elapsedMs + "ms");
        }
    }

    /**
     * 测试 attach 已存在的队列
     */
    @Test
    void testAttachExisting() {
        String name = uniqueName();
        try (ShmQueue c1 = ShmQueue.create(name, 8, 128, ShmQueue.Mode.HYBRID)) {
            c1.send(100, "from-creator".getBytes());
            try (ShmQueue c2 = ShmQueue.attach(name)) {
                ShmQueue.Message msg = c2.recv();
                assertEquals(100, msg.type());
                assertArrayEquals("from-creator".getBytes(), msg.bytes());
            }
        }
    }

    /**
     * 测试大批量消息
     */
    @Test
    void testLargeBurst() {
        String name = uniqueName();
        try (ShmQueue q = ShmQueue.create(name, 1024, 4096, ShmQueue.Mode.HYBRID)) {
            int n = 5000;
            List<Integer> sent = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                q.send(0xAB, intToBytes(i));
                sent.add(i);
            }
            for (int i = 0; i < n; i++) {
                ShmQueue.Message m = q.recv();
                assertEquals(0xAB, m.type());
                assertEquals(i, bytesToInt(m.bytes()));
            }
        }
    }

    /**
     * 生成唯一名字
     */
    private static String uniqueName() {
        return "/shmq_test_" + System.nanoTime();
    }

    /** IntToBytes */
    private static byte[] intToBytes(int v) {
        return new byte[]{
                (byte) ((v >> 24) & 0xFF),
                (byte) ((v >> 16) & 0xFF),
                (byte) ((v >> 8) & 0xFF),
                (byte) (v & 0xFF)
        };
    }

    /** BytesToInt */
    private static int bytesToInt(byte[] b) {
        if (b == null || b.length != 4) {
            return -1;
        }
        return ((b[0] & 0xFF) << 24)
                | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8)
                | (b[3] & 0xFF);
    }
}
