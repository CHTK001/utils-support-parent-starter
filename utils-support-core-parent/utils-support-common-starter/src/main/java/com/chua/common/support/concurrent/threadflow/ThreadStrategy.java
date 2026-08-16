package com.chua.common.support.concurrent.threadflow;

/**
 * 多任务合并策略。
 *
 * <p>用于 {@link ThreadFlow} 在多个任务执行完成后决定整体结果何时确定：
 * <ul>
 *     <li>{@link #ANY_SUCCESS}：任意一个任务成功即视为整体成功</li>
 *     <li>{@link #ALL_SUCCESS}：所有任务都成功才视为整体成功</li>
 *     <li>{@link #ANY_FAIL}：任意一个任务失败即视为整体失败</li>
 *     <li>{@link #N_FAIL}：累计 N 个任务失败即视为整体失败</li>
 *     <li>{@link #N_SUCCESS}：累计 N 个任务成功即视为整体成功</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/15
 */
public enum ThreadStrategy {

    /**
     * 任意一个任务成功即视为整体成功
     */
    ANY_SUCCESS,

    /**
     * 所有任务都成功才视为整体成功
     */
    ALL_SUCCESS,

    /**
     * 任意一个任务失败即视为整体失败
     */
    ANY_FAIL,

    /**
     * 累计 N 个任务失败即视为整体失败
     */
    N_FAIL,

    /**
     * 累计 N 个任务成功即视为整体成功
     */
    N_SUCCESS
}
