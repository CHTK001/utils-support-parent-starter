package com.chua.common.support.rule;

/**
 * 规则事件监听器。
 *
 * <p>用于观测规则引擎运行过程，实现审计、指标与调试旁路。
 * 监听器回调运行在推理线程内，<b>不应执行阻塞或耗时操作</b>，
 * 耗时场景请自行转投到独立线程池。</p>
 *
 * <h3>异常约定</h3>
 * <p>监听器抛出的异常会被引擎吞掉并计入 {@link RuleSession#failedCount()}，
 * 不会影响规则执行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@FunctionalInterface
public interface RuleListener {

    /**
     * 无操作监听器。
     */
    RuleListener NOOP = event -> {
    };

    /**
     * 接收规则事件。
     *
     * @param event 规则事件
     */
    void onEvent(RuleEvent event);
}
