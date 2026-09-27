package com.chua.common.support.ai.decision;

/**
 * 概率决策层异常。
 *
 * <p>用于表达「决策流程本身出了问题」，与调用方业务异常区分开：
 * 本异常意味着无法得出可信的决策结论（请求构造非法、结果不自洽、
 * 实现方内部故障），调用方通常应<b>中止</b>而非降级——因为规则阈值
 * 判断建立在概率之上，缺一个答案就继续跑等于用错误前提做决策。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DecisionException extends RuntimeException {

    /**
     * 构造决策层异常。
     *
     * @param message 异常消息
     */
    public DecisionException(String message) {
        super(message);
    }

    /**
     * 构造决策层异常。
     *
     * @param message 异常消息
     * @param cause   根因
     */
    public DecisionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 构造带问题标识的决策层异常。
     *
     * <p>决策失败几乎总是「某个具体问题出的问题」，把标识直接拼进消息
     * 比让调用方自己回溯调用栈定位要快得多。</p>
     *
     * @param queryId 问题标识
     * @param message 异常消息
     */
    public DecisionException(String queryId, String message) {
        super("问题 " + queryId + " 决策失败: " + message);
    }
}
