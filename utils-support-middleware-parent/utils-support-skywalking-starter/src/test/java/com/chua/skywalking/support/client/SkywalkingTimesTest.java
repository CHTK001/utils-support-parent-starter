package com.chua.skywalking.support.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 时间点解析：OAP 返回的紧凑时间桶。
 * <p>
 * 这条用例锁的是一个<b>看不见的</b>真实格式。协议文件 {@code metrics-v2.graphqls} 只说
 * {@code KVInt.id: ID!}，没写它长什么样；查询侧的 {@code Duration} 用的是带分隔符的
 * {@code yyyy-MM-dd HHmm}，很容易让人以为返回值也是同一种写法。
 * </p>
 * <p>
 * 但 OAP 实现里 {@code PointOfTime#generateId()} 拼的是
 * <b>{@code <紧凑时间桶>_<entityId>}</b>，紧凑时间桶用 {@code yyyyMMddHHmm} 这类
 * 不带分隔符的格式。实测真实 OAP 9.7.0 返回：
 * </p>
 * <pre>
 * SECOND: 20261104070000_ILLEGAL_ENTITY
 * MINUTE: 202611040700_ILLEGAL_ENTITY
 * HOUR:   2026110407_ILLEGAL_ENTITY
 * DAY:    20261104_ILLEGAL_ENTITY
 * </pre>
 * <p>
 * 解析失败时 {@link SkywalkingTimes#parse} 返回 0，调用方会丢弃该点 ——
 * 于是格式一旦不匹配，表现是<b>指标图整张空白且不报错</b>，这是最容易被漏掉的故障形态。
 * </p>
 *
 * @author CH
 * @since 4.0.0.51
 */
class SkywalkingTimesTest {

    /**
     * 与生产代码一致的时区，避免断言受运行机器时区影响
     */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /**
     * 构造期望的毫秒值
     */
    private static long millis(int y, int mo, int d, int h, int mi, int s) {
        return LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZONE).toInstant().toEpochMilli();
    }

    @Test
    @DisplayName("MINUTE：202611040700_ILLEGAL_ENTITY → 2026-11-04 07:00")
    void parsesMinuteBucketWithEntitySuffix() {
        long actual = SkywalkingTimes.parse("202611040700_ILLEGAL_ENTITY", "MINUTE");
        assertEquals(millis(2026, 11, 4, 7, 0, 0), actual,
                "必须取 _ 之前的时间桶，且紧凑格式不带分隔符");
        assertNotEquals(0L, actual, "返回 0 意味着该点会被静默丢弃");
    }

    @Test
    @DisplayName("SECOND / HOUR / DAY 四种步长的紧凑时间桶都能解析")
    void parsesAllCompactSteps() {
        assertEquals(millis(2026, 11, 4, 7, 0, 0),
                SkywalkingTimes.parse("20261104070000_ENTITY", "SECOND"));
        assertEquals(millis(2026, 11, 4, 7, 0, 0),
                SkywalkingTimes.parse("2026110407_ENTITY", "HOUR"));
        assertEquals(millis(2026, 11, 4, 0, 0, 0),
                SkywalkingTimes.parse("20261104_ENTITY", "DAY"));
    }

    @Test
    @DisplayName("没有 entityId 后缀的裸时间桶也能解析")
    void parsesBucketWithoutSuffix() {
        assertEquals(millis(2026, 11, 4, 7, 0, 0), SkywalkingTimes.parse("202611040700", "MINUTE"));
    }

    @Test
    @DisplayName("分钟/秒非 0 时必须原样解析，不能被截到整点")
    void keepsNonZeroMinuteAndSecond() {
        // 这条是「24 小时按分钟查只回来 25 个点」那个 bug 的回归。
        // 如果解析实现把分钟/秒丢了，下面三个断言会退化成同一个整点时间。
        assertEquals(millis(2026, 11, 4, 7, 35, 0),
                SkywalkingTimes.parse("202611040735_ENTITY", "MINUTE"),
                "分钟位被丢弃时，所有点都会压到整点");
        assertEquals(millis(2026, 11, 4, 7, 35, 42),
                SkywalkingTimes.parse("20261104073542_ENTITY", "SECOND"),
                "秒位被丢弃时会退化成分钟桶");
        assertEquals(millis(2026, 11, 4, 23, 59, 59),
                SkywalkingTimes.parse("20261104235959_ENTITY", "SECOND"));
        // 同一分钟内的两个点必须解析成不同时间，否则会被 Map 合并成一个点
        assertNotEquals(
                SkywalkingTimes.parse("202611040735_ENTITY", "MINUTE"),
                SkywalkingTimes.parse("202611040736_ENTITY", "MINUTE"),
                "相邻两个分钟桶必须解析成不同时间戳");
    }

    @Test
    @DisplayName("仍兼容带分隔符的形态（不因修新格式而破坏旧路径）")
    void stillAcceptsSeparatedForm() {
        assertEquals(millis(2026, 11, 4, 7, 0, 0), SkywalkingTimes.parse("2026-11-04 0700", "MINUTE"));
        assertEquals(millis(2026, 11, 4, 7, 0, 0), SkywalkingTimes.parse("2026-11-04 07", "HOUR"));
        assertEquals(millis(2026, 11, 4, 0, 0, 0), SkywalkingTimes.parse("2026-11-04", "DAY"));
    }

    @Test
    @DisplayName("无法解析时返回 0（调用方据此丢点），且不抛异常")
    void returnsZeroForGarbage() {
        assertEquals(0L, SkywalkingTimes.parse(null, "MINUTE"));
        assertEquals(0L, SkywalkingTimes.parse("", "MINUTE"));
        assertEquals(0L, SkywalkingTimes.parse("   ", "MINUTE"));
        assertEquals(0L, SkywalkingTimes.parse("not-a-time", "MINUTE"));
        assertEquals(0L, SkywalkingTimes.parse("99999999", "DAY"), "非法月日不应抛异常");
    }
}
