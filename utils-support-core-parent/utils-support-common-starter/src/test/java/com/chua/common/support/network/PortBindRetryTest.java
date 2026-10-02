package com.chua.common.support.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.BindException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端口绑定冲突退避重试 —— 策略本身的判定与退避行为。
 *
 * <p>覆盖的是「重启时旧进程还没退出」这个竞态的自愈能力：bind 拿到
 * {@link BindException} 之后应当继续尝试，而不是一次失败就把节点永久留在 ERROR。</p>
 *
 * <p>退避时长通过注入假 {@link PortBindRetry.Sleeper} 断言，而不是靠真实等待：
 * 真睡一遍只能测出「没抛异常」，测不出退避到底是不是递增、是不是被上限截断，
 * 还会让整个套件慢上几秒到几十秒。</p>
 *
 * <p>其中「非端口冲突立即失败」是本类的<b>敏感性对照</b>：若把判定写成
 * 对所有 {@link RuntimeException} 一律重试，该用例会因尝试次数变成多次而失败，
 * 从而挡住「用无差别重试掩盖真实配置错误」这种退化实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class PortBindRetryTest {

    /**
     * 记录每次等待时长的假 Sleeper。
     */
    private static final class RecordingSleeper implements PortBindRetry.Sleeper {

        private final List<Long> delays = new ArrayList<>();

        private final AtomicInteger calls = new AtomicInteger();

        private final int interruptOnCall;

        private RecordingSleeper() {
            this(-1);
        }

        private RecordingSleeper(int interruptOnCall) {
            this.interruptOnCall = interruptOnCall;
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            delays.add(millis);
            if (calls.incrementAndGet() == interruptOnCall) {
                throw new InterruptedException("测试注入的中断");
            }
        }
    }

    /**
     * 构造与各 server 实现同形的端口冲突异常。
     *
     * <p>刻意模拟真实包装层级：{@code FtpsServer.doStart} 抛的是
     * {@code IllegalStateException("FTPS 服务器启动失败", BindException)}，
     * 而 {@code FtpServer} 还多包了一层。只把 BindException 直接抛出的写法，
     * 会让「必须遍历 cause 链」这个要求失去意义。</p>
     *
     * @param endpoint 端点描述
     * @return 端口冲突异常
     */
    private static RuntimeException bindConflict(String endpoint) {
        return new IllegalStateException(endpoint + " 启动失败",
                new IOException("bind 失败", new BindException("Address already in use")));
    }

    @Test
    @DisplayName("首次就成功时不得发生任何等待")
    void successOnFirstAttemptDoesNotWait() {
        AtomicInteger attempts = new AtomicInteger();
        RecordingSleeper sleeper = new RecordingSleeper();

        PortBindRetry.retry("ftps://127.0.0.1:21",
                new PortBindRetry.Policy(5, 250L, 4000L, 60_000L),
                attempts::incrementAndGet, sleeper);

        assertEquals(1, attempts.get(), "首次成功只应执行一次");
        assertTrue(sleeper.delays.isEmpty(), "首次成功不应等待，实际等了 " + sleeper.delays);
    }

    @Test
    @DisplayName("端口冲突后退避重试，重试成功即返回")
    void retriesUntilBindSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        RecordingSleeper sleeper = new RecordingSleeper();

        PortBindRetry.retry("ftps://127.0.0.1:21",
                new PortBindRetry.Policy(5, 250L, 4000L, 60_000L),
                () -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw bindConflict("ftps://127.0.0.1:21");
                    }
                }, sleeper);

        assertEquals(3, attempts.get(), "前两次冲突后第三次应当成功");
        assertEquals(2, sleeper.delays.size(), "两次失败对应两次等待");
    }

    @Test
    @DisplayName("非端口冲突必须立即失败且只尝试一次（敏感性对照：禁止无差别重试）")
    void nonBindFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        RecordingSleeper sleeper = new RecordingSleeper();
        IllegalStateException real = new IllegalStateException("FTPS SSL 初始化失败：证书损坏");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> PortBindRetry.retry("ftps://127.0.0.1:21",
                        new PortBindRetry.Policy(5, 250L, 4000L, 60_000L),
                        () -> {
                            attempts.incrementAndGet();
                            throw real;
                        }, sleeper));

        assertSame(real, thrown, "必须原样抛出调用方的异常，而不是包一层");
        assertEquals(1, attempts.get(), "证书损坏这类错误与端口无关，重试只会拖满预算");
        assertTrue(sleeper.delays.isEmpty(), "不应有任何等待");
    }

    @Test
    @DisplayName("尝试次数用尽后失败，且异常链里保留原始 BindException")
    void failsAfterAttemptsExhausted() {
        AtomicInteger attempts = new AtomicInteger();
        RecordingSleeper sleeper = new RecordingSleeper();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> PortBindRetry.retry("ftps://127.0.0.1:21",
                        new PortBindRetry.Policy(3, 1L, 1L, 60_000L),
                        () -> {
                            attempts.incrementAndGet();
                            throw bindConflict("ftps://127.0.0.1:21");
                        }, sleeper));

        assertEquals(3, attempts.get(), "maxAttempts=3 应恰好尝试三次");
        assertEquals(2, sleeper.delays.size(), "最后一次失败后不再等待");
        assertTrue(PortBindRetry.isBindConflict(thrown),
                "包装后的异常必须仍能追溯到 BindException，否则上层无法判断是端口问题");
    }

    @Test
    @DisplayName("失败信息要写明端点与重试次数，否则无从判断该找谁")
    void failureMessageCarriesEndpointAndAttempts() {
        // 真实故障排查第一步就是「谁的哪个端口、试了几次」，所以这两项必须在信息里
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> PortBindRetry.retry("ftps://0.0.0.0:21",
                        new PortBindRetry.Policy(2, 1L, 1L, 60_000L),
                        () -> {
                            throw bindConflict("ftps://0.0.0.0:21");
                        }, new RecordingSleeper()));

        assertTrue(thrown.getMessage().contains("ftps://0.0.0.0:21"),
                "异常信息应含端点，实际=" + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("2"),
                "异常信息应含重试次数，实际=" + thrown.getMessage());
    }

    @Test
    @DisplayName("总预算耗尽后立即停止，不再空等剩余次数")
    void budgetGuardStopsFurtherWaiting() {
        AtomicInteger attempts = new AtomicInteger();
        RecordingSleeper sleeper = new RecordingSleeper();

        assertThrows(IllegalStateException.class,
                () -> PortBindRetry.retry("ftps://127.0.0.1:21",
                        new PortBindRetry.Policy(12, 1L, 1L, 0L),
                        () -> {
                            attempts.incrementAndGet();
                            throw bindConflict("ftps://127.0.0.1:21");
                        }, sleeper));

        assertEquals(1, attempts.get(), "预算是 0，首次冲突后就不该再试，也不该等");
        assertTrue(sleeper.delays.isEmpty(), "预算已耗尽，不应发生等待");
    }

    @Test
    @DisplayName("退避按 2 倍递增并被单次上限截断")
    void backoffDoublesAndIsCapped() {
        RecordingSleeper sleeper = new RecordingSleeper();

        assertThrows(IllegalStateException.class,
                () -> PortBindRetry.retry("ftps://127.0.0.1:21",
                        new PortBindRetry.Policy(5, 250L, 1000L, 60_000L),
                        () -> {
                            throw bindConflict("ftps://127.0.0.1:21");
                        }, sleeper));

        assertEquals(List.of(250L, 500L, 1000L, 1000L), sleeper.delays,
                "应先 250→500→1000，之后被上限 1000 截断");
    }

    @Test
    @DisplayName("noRetry 策略只尝试一次且不等待")
    void noRetryPolicyFailsImmediately() {
        AtomicInteger attempts = new AtomicInteger();
        RecordingSleeper sleeper = new RecordingSleeper();

        assertThrows(IllegalStateException.class,
                () -> PortBindRetry.retry("ftps://127.0.0.1:21", PortBindRetry.Policy.noRetry(),
                        () -> {
                            attempts.incrementAndGet();
                            throw bindConflict("ftps://127.0.0.1:21");
                        }, sleeper));

        assertEquals(1, attempts.get(), "未启用重试时应只执行一次");
        assertTrue(sleeper.delays.isEmpty(), "未启用重试时不应等待");
    }

    @Test
    @DisplayName("等待期间被中断要立刻中止，并保留中断标志")
    void interruptDuringBackoffAbortsPromptly() {
        RecordingSleeper sleeper = new RecordingSleeper(1);
        AtomicInteger attempts = new AtomicInteger();

        try {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> PortBindRetry.retry("ftps://127.0.0.1:21",
                            new PortBindRetry.Policy(5, 250L, 4000L, 60_000L),
                            () -> {
                                attempts.incrementAndGet();
                                throw bindConflict("ftps://127.0.0.1:21");
                            }, sleeper));

            assertInstanceOf(InterruptedException.class, thrown.getCause(),
                    "中断应作为 cause 保留下来");
            assertTrue(Thread.currentThread().isInterrupted(),
                    "必须恢复中断标志，否则上层 shutdown 会丢掉取消信号继续空转");
        } finally {
            // 清掉标志，避免污染同一线程上的后续测试
            Thread.interrupted();
        }
        assertEquals(1, attempts.get(), "被中断后不应再尝试第二次");
    }

    @Test
    @DisplayName("isBindConflict 只认端口冲突，且能穿透多层包装")
    void bindConflictDetection() {
        assertTrue(PortBindRetry.isBindConflict(bindConflict("ftps")),
                "真实 server 实现就是多层包装，必须能穿透");
        assertTrue(PortBindRetry.isBindConflict(new BindException("Address already in use")),
                "未包装的 BindException 也应识别");
        assertFalse(PortBindRetry.isBindConflict(new IllegalStateException("证书损坏")),
                "与端口无关的异常不能判成可重试");
        assertFalse(PortBindRetry.isBindConflict(null), "null 应安全返回 false");
    }

    @Test
    @DisplayName("非法策略参数被拒绝，避免运行期出现无意义的忙等")
    void policyRejectsIllegalValues() {
        assertThrows(IllegalArgumentException.class, () -> new PortBindRetry.Policy(-1, 250L, 4000L, 30_000L));
        assertThrows(IllegalArgumentException.class, () -> new PortBindRetry.Policy(3, -1L, 4000L, 30_000L));
        assertThrows(IllegalArgumentException.class, () -> new PortBindRetry.Policy(3, 250L, -1L, 30_000L));
        assertThrows(IllegalArgumentException.class, () -> new PortBindRetry.Policy(3, 250L, 4000L, -1L));
    }

    @Test
    @DisplayName("配置项写错时回退默认，而不是让应用起不来")
    void malformedPropertyFallsBackToDefault() {
        PortBindRetry.Policy expected = PortBindRetry.parse(null);

        for (String bad : List.of("abc", "12:250", "12:250:4000:30000:1", "12:x:4000:30000", "")) {
            PortBindRetry.Policy parsed = PortBindRetry.parse(bad);
            assertEquals(expected, parsed, "配置 \"" + bad + "\" 应回退到默认策略");
        }

        PortBindRetry.Policy ok = PortBindRetry.parse("3:100:200:999");
        assertEquals(3, ok.maxAttempts());
        assertEquals(100L, ok.initialDelayMs());
        assertEquals(200L, ok.maxDelayMs());
        assertEquals(999L, ok.totalBudgetMs());
        assertTrue(ok.enabled());
    }
}