package com.chua.common.support.task.cooldown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 退避策略用例：固定、指数、线性、不冷却与封顶。
 *
 * @author CH
 * @since 4.0.0.53
 */
class BackoffTest {

    /**
     * 固定时长：无论失败多少次都一样
     */
    @Test
    void fixedIgnoresFailures() {
        assertEquals(30_000L, Backoff.fixed().delayMillis(1, 30_000L, 0L));
        assertEquals(30_000L, Backoff.fixed().delayMillis(9, 30_000L, 0L));
    }

    /**
     * 指数退避：60s → 2m → 4m，封顶生效
     */
    @Test
    void exponentialDoublesAndCaps() {
        assertEquals(60_000L, Backoff.exponential().delayMillis(1, 60_000L, 0L));
        assertEquals(120_000L, Backoff.exponential().delayMillis(2, 60_000L, 0L));
        assertEquals(300_000L, Backoff.exponential().delayMillis(9, 60_000L, 300_000L));
    }

    /**
     * 线性退避：基础 × 次数，封顶生效
     */
    @Test
    void linearGrowsByFailures() {
        assertEquals(30_000L, Backoff.linear().delayMillis(1, 30_000L, 0L));
        assertEquals(90_000L, Backoff.linear().delayMillis(3, 30_000L, 0L));
        assertEquals(120_000L, Backoff.linear().delayMillis(9, 30_000L, 120_000L));
    }

    /**
     * 不冷却：只记事件不排除
     */
    @Test
    void noneNeverCoolsDown() {
        assertEquals(0L, Backoff.none().delayMillis(3, 60_000L, 600_000L));
    }

    /**
     * 按名字取策略，未知回落指数
     */
    @Test
    void ofResolvesByName() {
        assertEquals(30_000L, Backoff.of("fixed").delayMillis(3, 30_000L, 0L));
        assertEquals(0L, Backoff.of("none").delayMillis(3, 30_000L, 0L));
        assertEquals(60_000L, Backoff.of("unknown").delayMillis(1, 60_000L, 0L));
        assertEquals(60_000L, Backoff.of(null).delayMillis(1, 60_000L, 0L));
    }

    /**
     * 负基础时长归零，不产生负冷却
     */
    @Test
    void negativeBaseIsSafe() {
        assertEquals(0L, Backoff.exponential().delayMillis(1, -1L, 0L));
        assertEquals(0L, Backoff.linear().delayMillis(5, -1L, 0L));
    }
}
