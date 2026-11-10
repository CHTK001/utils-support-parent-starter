package com.chua.common.support.task.cooldown;

/**
 * 冷却到期调度器 —— 决定「到期这件事件怎么被感知」。
 *
 * <p>两种实现：</p>
 * <ul>
 *   <li>{@link #lazy()}：<b>惰性过期</b>。不占线程、不排任务，读的时候顺手清掉过期记录。
 *       绝大多数场景够用（{@link CooldownGate} 默认就是这个）；</li>
 *   <li>{@link #timeWheel()}：<b>时间轮驱动</b>。到期主动回调，用于「恢复要通知外界」
 *       或「按恢复顺序先进先出处理」的场景。</li>
 * </ul>
 *
 * <p>调度器只负责唤醒，真正删状态、发恢复事件仍然由 {@link CooldownGate} 做，
 * 因此两种实现可以随时切换，状态语义完全一致。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
public interface ExpiryScheduler {

    /**
     * 续约一个键的到期唤醒。
     *
     * <p>同一个 {@code scope + key} 重复调用时，旧任务必须先被取消，
     * 保证「连续失败改到期时间」不会出现提前唤醒。</p>
     *
     * @param scope       作用域
     * @param key         冷却键
     * @param delayMillis 延迟毫秒数
     * @param onExpire    到期回调
     */
    void schedule(String scope, String key, long delayMillis, Runnable onExpire);

    /**
     * 取消某个键的到期唤醒（成功恢复时调用）。
     *
     * @param scope 作用域
     * @param key   冷却键
     */
    void cancel(String scope, String key);

    /**
     * 清空某个作用域下的全部唤醒任务（重置时调用）。
     *
     * @param scope 作用域
     */
    void clearScope(String scope);

    /**
     * 关闭调度器，释放线程资源。
     */
    default void shutdown() {
    }

    /**
     * 惰性过期实现：不排期、不占线程。
     *
     * @return 调度器
     */
    static ExpiryScheduler lazy() {
        return LazyExpiryScheduler.INSTANCE;
    }

    /**
     * 时间轮实现：到期主动回调，同到期时刻按登记顺序先进先出。
     *
     * @return 调度器
     */
    static ExpiryScheduler timeWheel() {
        return new TimeWheelExpiryScheduler();
    }

    /**
     * 惰性实现（无状态单例）。
     */
    final class LazyExpiryScheduler implements ExpiryScheduler {

        /**
         * 单例
         */
        static final LazyExpiryScheduler INSTANCE = new LazyExpiryScheduler();

        private LazyExpiryScheduler() {
        }

        @Override
        public void schedule(String scope, String key, long delayMillis, Runnable onExpire) {
            // 惰性实现不排期：读取时由 CooldownGate 判断是否过期
        }

        @Override
        public void cancel(String scope, String key) {
            // 无任务可取消
        }

        @Override
        public void clearScope(String scope) {
            // 无任务可清
        }
    }
}
