package com.chua.datalake.support.manager;

import lombok.extern.slf4j.Slf4j;

/**
 * 订阅管理器，负责 subscriberId↔offset 映射与实时推送调度。
 *
 * <p>当前版本提供内存实现；后续版本会通过 {@code OffsetFlow} 结合 Redis 进行持久化。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class SubscriberManager {

    /**
     * 构造订阅管理器
     */
    public SubscriberManager() {
    }

    /**
     * 启动订阅管理器
     */
    public void start() {
        log.info("SubscriberManager started");
    }

    /**
     * 停止订阅管理器
     */
    public void stop() {
        log.info("SubscriberManager stopped");
    }
}