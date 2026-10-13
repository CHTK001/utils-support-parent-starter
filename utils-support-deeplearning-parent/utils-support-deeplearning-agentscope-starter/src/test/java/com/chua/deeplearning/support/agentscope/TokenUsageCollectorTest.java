package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.AiUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenUsageCollector} 的回归测试。
 *
 * <p>对应缺陷：{@code AgentScopeAgent.run(String)} 走阻塞 call 路径时从不回填
 * {@code AgentResponse.usage}，而 {@code goalTurn} → {@code tokenOf} 依赖它，
 * 导致目标循环的令牌统计恒为 0。本类锁定收集器的取数语义。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class TokenUsageCollectorTest {

    @Test
    @DisplayName("未观测到用量时产出 null，而不是伪造零值")
    void emptyProducesNull() {
        TokenUsageCollector collector = new TokenUsageCollector();

        assertTrue(collector.isEmpty());
        assertNull(collector.toUsage(1000L, 50L),
                "没有用量就不该造一条零值记录");
    }

    @Test
    @DisplayName("累加多次模型调用并汇总 total = 输入 + 输出")
    void aggregatesAcrossCalls() {
        TokenUsageCollector collector = new TokenUsageCollector();
        collector.add(100, 20, 30);
        collector.add(50, 5, 10);

        AiUsage usage = collector.toUsage(1000L, 250L);

        assertNotNull(usage, "有用量时必须产出记录");
        assertEquals(Integer.valueOf(150), usage.getInputTokens());
        assertEquals(Integer.valueOf(25), usage.getOutputTokens());
        assertEquals(Integer.valueOf(40), usage.getCacheTokens());
        assertEquals(Integer.valueOf(175), usage.getTotalTokens(), "total 应为输入加输出，不含缓存");
        assertEquals(Long.valueOf(1000L), usage.getStartTime());
        assertEquals(Long.valueOf(250L), usage.getDurationMillis());
    }

    @Test
    @DisplayName("负值与非正令牌视为未提供，不污染累加")
    void ignoresNonPositive() {
        TokenUsageCollector collector = new TokenUsageCollector();
        collector.add(-5, 0, 0);

        assertTrue(collector.isEmpty(), "非正令牌不应计入");

        collector.add(10, 0, 0);
        AiUsage usage = collector.toUsage(0L, 0L);
        assertNotNull(usage);
        assertEquals(Integer.valueOf(10), usage.getInputTokens());
        assertEquals(Integer.valueOf(0), usage.getOutputTokens());
    }

    @Test
    @DisplayName("并发累加不丢更新")
    void concurrentAddsAreSafe() throws InterruptedException {
        TokenUsageCollector collector = new TokenUsageCollector();
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        collector.add(1, 1, 0);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务应按时完成");
        pool.shutdownNow();

        AiUsage usage = collector.toUsage(0L, 0L);
        assertNotNull(usage);
        assertEquals(Integer.valueOf(threads * perThread), usage.getInputTokens(),
                "并发累加不得丢失增量");
        assertEquals(Integer.valueOf(threads * perThread), usage.getOutputTokens());
    }
}
