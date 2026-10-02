package com.chua.common.support.network;

import lombok.extern.slf4j.Slf4j;

import java.net.BindException;

/**
 * 端口绑定冲突的退避重试策略。
 *
 * <p>解决的是<b>重启时的跨进程端口竞争</b>：应用重启后旧 JVM 还在优雅关闭
 * （本仓实测要 30~40s，因为 {@code AbstractServer.stop()} 会等活跃请求跑完），
 * 而新 JVM 的 {@code ApplicationReadyEvent} 已触发自动拉起，两边抢同一个端口，
 * 后 bind 的一方拿到 {@link BindException}。表现为节点状态 ERROR、日志报
 * Address already in use，而这个端口几秒后就空出来了。</p>
 *
 * <p>实测过端口冲突能否在一次运行内自然消失：持有端口的套接字 close 之后，
 * 开着 {@code setReuseAddress(true)} 可以立刻重绑（TIME_WAIT 不构成障碍），
 * 因此唯一的阻塞者就是「还在活跃监听的另一个进程」。这正是需要退避等待而不是
 * 立刻放弃的原因。</p>
 *
 * <p><b>只对端口冲突重试</b>：{@link BindException} 之外的异常立即原样抛出。
 * 无差别重试会把「端口被一个不该存在的服务长期占用」「证书损坏」这类真实配置错误
 * 也拖满整个预算才报错，反而更难定位。</p>
 *
 * <p><b>为什么收敛在这里而不是各改一遍 doStart()</b>：仓里有 47 处
 * {@code doStart()} 实现，逐个加重试既不一致也必然漏。端口绑定是所有
 * {@code AbstractServer} 子类的共同第一步，策略放在调用点才有一处可测、一处可改。</p>
 *
 * <p>等待总预算默认 30s，是对齐实测的优雅关闭时长；超过这个时间端口还没空出来，
 * 基本可以断定占用方不是「上一个进程退出慢」，继续等只是浪费时间，
 * 因此到点即失败并给出可诊断信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class PortBindRetry {

    /**
     * 覆盖默认重试策略的系统属性。
     *
     * <p>两种写法：{@code 12:250:4000:30000} 给出全部四项，或只给最大尝试次数
     * {@code 12}（其余取默认）。{@code 0} 表示关闭重试。</p>
     */
    public static final String POLICY_PROPERTY = "com.chua.support.network.port-bind-retry";

    /**
     * 默认最大尝试次数（含首次）。
     */
    private static final int DEFAULT_MAX_ATTEMPTS = 12;

    /**
     * 默认首次退避毫秒。
     */
    private static final long DEFAULT_INITIAL_DELAY_MS = 250L;

    /**
     * 默认单次退避上限毫秒。
     */
    private static final long DEFAULT_MAX_DELAY_MS = 4_000L;

    /**
     * 默认总预算毫秒。
     */
    private static final long DEFAULT_TOTAL_BUDGET_MS = 30_000L;

    private PortBindRetry() {
        // 工具类不允许实例化
    }

    /**
     * 退避等待的抽象。
     *
     * <p>抽出来是为了让测试能注入假实现，从而断言「退避确实在递增」这类行为，
     * 而不必真的睡 30s —— 真睡一遍既慢又只能测出「没抛异常」。</p>
     */
    @FunctionalInterface
    public interface Sleeper {

        /**
         * 等待指定毫秒。
         *
         * @param millis 毫秒数
         * @throws InterruptedException 等待期间被中断
         */
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * 重试策略。
     *
     * @param maxAttempts 最大尝试次数（含首次）；0 表示完全不重试、首次失败即抛出
     * @param initialDelayMs 首次退避毫秒
     * @param maxDelayMs 单次退避上限毫秒
     * @param totalBudgetMs 总预算毫秒，超过后不再尝试
     */
    public record Policy(int maxAttempts, long initialDelayMs, long maxDelayMs, long totalBudgetMs) {

        /**
         * 紧凑构造器，校验取值范围。
         */
        public Policy {
            if (maxAttempts < 0) {
                throw new IllegalArgumentException("maxAttempts 不能为负数，0 表示不重试");
            }
            if (initialDelayMs < 0L || maxDelayMs < 0L || totalBudgetMs < 0L) {
                throw new IllegalArgumentException("退避时长与总预算不能为负数");
            }
        }

        /**
         * 是否启用重试。
         *
         * @return 需要重试返回 true
         */
        public boolean enabled() {
            return maxAttempts > 0;
        }

        /**
         * 不重试的策略，用于 fail-fast 场景与测试。
         *
         * @return 首次失败即抛出的策略
         */
        public static Policy noRetry() {
            return new Policy(0, 0L, 0L, 0L);
        }
    }

    /**
     * 取默认策略，先看系统属性是否覆盖。
     *
     * @return 重试策略
     */
    public static Policy defaultPolicy() {
        return parse(System.getProperty(POLICY_PROPERTY));
    }

    /**
     * 解析重试策略配置，支持两种写法。
     *
     * <p>完整写法 {@code maxAttempts:initialDelayMs:maxDelayMs:totalBudgetMs}，
     * 或只写最大尝试次数 {@code maxAttempts}，其余沿用默认。</p>
     *
     * <p><b>单段简写是必需的，不是省事</b>：实测踩过这个坑 —— 用户想「关掉重试」
     * 会写 {@code -D...port-bind-retry=0}，若只接受 4 段，这个值会被判为格式非法
     * 然后回退默认策略，而默认策略<b>是带重试的</b>。于是「禁用」变成了「照常重试」，
     * 与意图完全相反，且只有一行 warn 混在启动日志里。方向反了的兜底比不兜底更危险。</p>
     *
     * <p>配置非法时<b>回退默认而不是抛错</b>：这是运维可选的调优项，少写一个冒号
     * 不该让整个应用起不来；回退时会打 warn，便于事后发现打错了。</p>
     *
     * @param raw 原始配置，{@code null} 或空白表示用默认值
     * @return 重试策略，解析失败时为默认策略
     */
    static Policy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaults();
        }
        String[] parts = raw.trim().split(":");
        try {
            if (parts.length == 1) {
                return new Policy(Integer.parseInt(parts[0].trim()),
                        DEFAULT_INITIAL_DELAY_MS, DEFAULT_MAX_DELAY_MS, DEFAULT_TOTAL_BUDGET_MS);
            }
            if (parts.length == 4) {
                return new Policy(Integer.parseInt(parts[0].trim()),
                        Long.parseLong(parts[1].trim()),
                        Long.parseLong(parts[2].trim()),
                        Long.parseLong(parts[3].trim()));
            }
        } catch (IllegalArgumentException e) {
            log.warn("{} 取值非法，已回退默认策略：{}（{}）", POLICY_PROPERTY, raw, e.getMessage());
            return defaults();
        }
        log.warn("{} 格式非法（应为 maxAttempts，或 maxAttempts:initialDelayMs:maxDelayMs:totalBudgetMs），"
                + "已回退默认策略：{}", POLICY_PROPERTY, raw);
        return defaults();
    }

    /**
     * 默认策略。
     *
     * @return 默认重试策略
     */
    private static Policy defaults() {
        return new Policy(DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_DELAY_MS,
                DEFAULT_MAX_DELAY_MS, DEFAULT_TOTAL_BUDGET_MS);
    }

    /**
     * 异常链里是否含端口绑定冲突。
     *
     * <p>必须整条链都看：各 server 实现都把 {@link BindException} 包进
     * {@link IllegalStateException} 再抛（例如 FTPS 的「FTPS 服务器启动失败」），
     * 只看最外层会把真实的端口冲突误判成不可重试，从而完全不生效。</p>
     *
     * @param error 待判断的异常
     * @return 含 {@link BindException} 返回 true
     */
    public static boolean isBindConflict(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof BindException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 端口绑定冲突时按退避策略重试。
     *
     * @param description 端点描述，进日志与异常信息
     * @param policy 重试策略；{@code null} 或未启用时直接执行一次
     * @param action 实际动作，成功即视为绑定成功
     * @throws IllegalStateException 持续冲突超出预算、或等待期间被中断
     */
    public static void retry(String description, Policy policy, Runnable action) {
        retry(description, policy, action, Thread::sleep);
    }

    /**
     * 端口绑定冲突时按退避策略重试，等待方式可注入。
     *
     * @param description 端点描述，进日志与异常信息
     * @param policy 重试策略；{@code null} 或未启用时直接执行一次
     * @param action 实际动作，成功即视为绑定成功
     * @param sleeper 退避等待实现
     * @throws IllegalStateException 持续冲突超出预算、或等待期间被中断
     */
    static void retry(String description, Policy policy, Runnable action, Sleeper sleeper) {
        if (action == null) {
            throw new IllegalArgumentException("action 不能为 null");
        }
        if (policy == null || !policy.enabled()) {
            action.run();
            return;
        }
        // 上限与首次延迟都强制至少 1ms：否则「有次数、零延迟」会退化成忙等，
        // 既烧 CPU 又让超时判断失去意义。
        long capMs = Math.max(1L, policy.maxDelayMs());
        long delayMs = Math.min(capMs, Math.max(1L, policy.initialDelayMs()));
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + policy.totalBudgetMs() * 1_000_000L;
        int attempt = 0;
        RuntimeException conflict = null;
        while (true) {
            attempt++;
            try {
                action.run();
                if (attempt > 1) {
                    log.info("{}：第 {} 次尝试绑定成功，此前等待约 {}ms", description, attempt, elapsedMs(startNanos));
                }
                return;
            } catch (RuntimeException e) {
                if (!isBindConflict(e)) {
                    throw e;
                }
                conflict = e;
                if (attempt >= policy.maxAttempts()) {
                    break;
                }
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs < delayMs) {
                    break;
                }
                try {
                    sleeper.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(description + "：等待端口释放期间被中断，放弃重试", interrupted);
                }
                delayMs = Math.min(capMs, delayMs * 2L);
            }
        }
        throw new IllegalStateException(description + "：端口绑定重试 " + attempt + " 次（累计等待约 "
                + elapsedMs(startNanos) + "ms）仍失败。若端口是被长期占用，通常不是上一个进程退出慢，"
                + "而是有别的服务常驻，请先确认占用方。", conflict);
    }

    /**
     * 已耗时毫秒数。
     *
     * <p>用 {@code System.nanoTime()} 而不是 {@code currentTimeMillis()}：后者会被
     * 墙上时间调整（对时、NTP 回拨）打断，跨 30s 的等待里完全可能出现负值或跳变，
     * 拿它算预算就会算错。</p>
     *
     * @param startNanos 起始纳秒
     * @return 已耗时毫秒
     */
    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}