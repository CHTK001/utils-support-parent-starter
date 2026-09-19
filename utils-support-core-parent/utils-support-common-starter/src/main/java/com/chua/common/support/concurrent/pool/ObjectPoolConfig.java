package com.chua.common.support.concurrent.pool;

import lombok.Builder;
import lombok.Data;

/**
 * 对象池配置
 *
 * <p>控制对象池的核心行为参数，包括容量、超时、空闲检测等。
 *
 * @author CH
 * @since 2026/07/16
 */
@Data
@Builder
public class ObjectPoolConfig {

    /**
     * 池最大容量
     *
     * <p>对象池中允许存在的最大对象数量（含空闲+借出）。
     * 达到上限时 borrow() 将阻塞等待归还或超时失败。
     * 默认 10。
     */
    @Builder.Default
    /**
     * 最大值总数
    */
    private int maxTotal = 10;

    /**
     * 最大空闲数
     *
     * <p>池中允许保留的最大空闲对象数量。超出的空闲对象将被销毁。
     * 默认等于 maxTotal。
     */
    private int maxIdle;

    /**
     * 最小空闲数
     *
     * <p>池中至少保持的空闲对象数量。不足时自动创建补充。
     * 默认 0（不预热）。
     */
    @Builder.Default
    /**
     * 最小值idle
    */
    private int minIdle = 0;

    /**
     * 借出超时时间（毫秒）
     *
     * <p>borrow() 等待可用对象的最大时间。超时抛出 {@link PoolTimeoutException}。
     * 默认 3000ms。
     */
    @Builder.Default
    /**
     * Borrow超时毫秒
    */
    private long borrowTimeoutMillis = 3000;

    /**
     * 空闲对象存活时间（毫秒）
     *
     * <p>空闲对象超过此时间未被使用将被销毁。默认 60000ms（1 分钟）。
     */
    @Builder.Default
    /**
     * Idle超时毫秒
    */
    private long idleTimeoutMillis = 60000;

    /**
     * 是否开启空闲检测
     *
     * <p>开启后定期清理超时的空闲对象。默认开启。
     */
    @Builder.Default
    /**
     * Idleeviction是否启用
    */
    private boolean idleEvictionEnabled = true;

    /**
     * 空闲检测间隔（毫秒）
     *
     * <p>每次检测的间隔时间。默认 30000ms（30 秒）。
     */
    @Builder.Default
    /**
     * Idleeviction间隔毫秒
    */
    private long idleEvictionIntervalMillis = 30000;

    /**
     * 借出时是否验证对象有效性
     *
     * <p>开启后每次 borrow() 会调用 validateObject() 检查对象是否可用。
     * 不可用则销毁并创建新对象。默认开启。
     */
    @Builder.Default
    /**
     * 测试ONborrow
    */
    private boolean testOnBorrow = true;

    /**
     * 归还时是否验证对象有效性
     *
     * <p>开启后每次 returnObject() 会调用 validateObject()。
     * 无效对象直接销毁不放回池中。默认开启。
     */
    @Builder.Default
    /**
     * 测试ON返回值
    */
    private boolean testOnReturn = true;
}
