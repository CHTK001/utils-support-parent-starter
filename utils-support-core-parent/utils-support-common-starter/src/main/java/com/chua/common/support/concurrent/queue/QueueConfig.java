package com.chua.common.support.concurrent.queue;

import lombok.Builder;
import lombok.Data;

/**
 * 无锁队列配置，封装队列类型与容量参数。
 *
 * <p>通过 Lombok 的 {@code @Builder} 生成构造器，默认使用 MPMC + 1024 容量。
 * 典型用法：前缀 {@code QueueConfig.builder().type(QueueType.SPSC).capacity(4096).build()}
 * 传入 {@link LockFreeQueueFlow#create(QueueConfig)}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class QueueConfig {

    /**
     * 队列类型，默认 MPMC
     */
    @Builder.Default
    /**
     * 类型
    */
    private QueueType type = QueueType.MPMC;

    /**
     * 有界队列容量，默认 1024；UNBOUNDED 类型时忽略该值
     */
    @Builder.Default
    /**
     * 容量
    */
    private int capacity = 1024;
}
