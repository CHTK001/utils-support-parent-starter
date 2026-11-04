package com.chua.skywalking.support.engine;

import com.chua.skywalking.support.client.SkywalkingTimes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标窗口切片：{@code readMetricsValues} 的 500 点上限。
 * <p>
 * 锁的是<b>与真实 OAP 的行为契约</b>，而不是实现细节。这条上限在 SkyWalking
 * 9.7.0 / 10.2.0 / 11.0.0 的协议文件里都看不到（它不在 schema 里，是查询实现的运行时校验），
 * 只能实测得出：
 * </p>
 * <ul>
 *   <li>跨度正好 500 个步长 → 正常返回；</li>
 *   <li>501 个 → 整条查询失败，错误为
 *       {@code Duration data error, the range between the start time and the end time can't exceed 500 minutes}；</li>
 *   <li>SECOND / MINUTE / HOUR / DAY 四种步长阈值一致 —— 限制的是<b>点数</b>，
 *       报错文案里的「500 minutes」只是以分钟步长举例。</li>
 * </ul>
 * <p>
 * 最容易踩的场景：前端选「最近 24 小时 + 分钟级」＝ 1440 个点，不切片就必然一条数据都没有。
 * </p>
 *
 * @author CH
 * @since 4.0.0.51
 */
class SkywalkingMetricWindowTest {

    /**
     * OAP 的单次查询点数上限
     */
    private static final int LIMIT = 500;

    /**
     * 反射调用私有方法 metricChunks(start, end, step)
     */
    @SuppressWarnings("unchecked")
    private static List<long[]> chunks(long start, long end, String step) throws Exception {
        Method method = SkywalkingOtnlEngine.class
                .getDeclaredMethod("metricChunks", long.class, long.class, String.class);
        method.setAccessible(true);
        return (List<long[]>) method.invoke(new SkywalkingOtnlEngine(), start, end, step);
    }

    /**
     * 断言所有片段都不超限，且拼起来覆盖整个窗口。
     * <p>
     * 断言用<b>跨度</b>而不是点数：真实 OAP 的限制是「跨度不超过 500 个步长」，
     * 跨度 500 步时可以（此时有 501 个点），501 步就报错。若按点数断言，
     * 会把本来合规的 500 步窗口判成超限。
     * </p>
     */
    private static void assertChunks(List<long[]> chunks, long start, long end, long stepMillis, long maxPoints) {
        assertTrue(!chunks.isEmpty(), "切片不能为空");
        long covered = 0;
        for (long[] chunk : chunks) {
            long spanInSteps = (chunk[1] - chunk[0]) / stepMillis;
            assertTrue(spanInSteps <= maxPoints,
                    "片段跨度 " + spanInSteps + " 步超过上限 " + maxPoints + " 步：[" + chunk[0] + "," + chunk[1] + "]");
            covered += chunk[1] - chunk[0];
        }
        // 相邻片段之间会跳过一个步长，允许因此产生的间隙
        long gaps = (long) (chunks.size() - 1) * stepMillis;
        assertEquals(end - start, covered + gaps, "片段必须正好铺满整个窗口，不能凭空丢一段");
    }

    @Test
    @DisplayName("窗口不超限时只发一次请求，不额外切片")
    void singleChunkWhenWithinLimit() throws Exception {
        long step = 60_000L;
        long start = 1_700_000_000_000L;
        long end = start + 30 * step;
        List<long[]> chunks = chunks(start, end, "MINUTE");

        assertEquals(1, chunks.size(), "30 分钟窗口不需要切");
        assertEquals(start, chunks.get(0)[0]);
        assertEquals(end, chunks.get(0)[1]);
    }

    @Test
    @DisplayName("正好 500 个步长不切（边界取等号）")
    void exactlyLimitNotSplit() throws Exception {
        long step = 60_000L;
        long start = 1_700_000_000_000L;
        long end = start + LIMIT * step;
        List<long[]> chunks = chunks(start, end, "MINUTE");

        assertEquals(1, chunks.size(), "正好 500 个点必须放行，否则会把本来合规的查询拆开");
        assertEquals(end, chunks.get(0)[1]);
    }

    @Test
    @DisplayName("501 个步长必须切：这是真实 OAP 的报错边界")
    void splitsOneOverLimit() throws Exception {
        long step = 60_000L;
        long start = 1_700_000_000_000L;
        long end = start + 501 * step;
        List<long[]> chunks = chunks(start, end, "MINUTE");

        assertEquals(2, chunks.size(), "501 个步长要切成两段");
        assertChunks(chunks, start, end, step, LIMIT);
    }

    @Test
    @DisplayName("1440 分钟窗口切成 3 段，每段跨度不超过 500 步")
    void splitsDayOfMinutes() throws Exception {
        long step = 60_000L;
        long start = 1_700_000_000_000L;
        long end = start + 1440 * step;
        List<long[]> chunks = chunks(start, end, "MINUTE");

        assertEquals(3, chunks.size(), "1440 个步长按 500 切应得 3 段（500 + 500 + 440）");
        assertChunks(chunks, start, end, step, LIMIT);
    }

    @Test
    @DisplayName("秒级同样按步长数切，不是按墙钟时间")
    void splitsByPointsNotByWallClock() throws Exception {
        long step = 1_000L;
        long start = 1_700_000_000_000L;
        // 800 秒 ≈ 13 分钟，墙钟上很短，但 800 个步长照样超限
        long end = start + 800 * step;
        List<long[]> chunks = chunks(start, end, "SECOND");

        assertEquals(2, chunks.size(), "800 个秒级步长也要切，说明限制按步长数而非墙钟时间");
        assertChunks(chunks, start, end, step, LIMIT);
    }

    @Test
    @DisplayName("天级同样按步长数切：1000 天要切两段")
    void splitsDays() throws Exception {
        long step = 86_400_000L;
        long start = 1_700_000_000_000L;
        long end = start + 1000 * step;
        List<long[]> chunks = chunks(start, end, "DAY");

        assertEquals(2, chunks.size());
        assertChunks(chunks, start, end, step, LIMIT);
    }

    @Test
    @DisplayName("步长毫秒换算覆盖四种步长，未知步长返回 0 而不抛异常")
    void stepMillisCoverage() {
        assertEquals(1_000L, SkywalkingTimes.stepMillis("SECOND"));
        assertEquals(60_000L, SkywalkingTimes.stepMillis("minute"), "大小写不敏感");
        assertEquals(3_600_000L, SkywalkingTimes.stepMillis("HOUR"));
        assertEquals(86_400_000L, SkywalkingTimes.stepMillis("DAY"));
        assertEquals(0L, SkywalkingTimes.stepMillis("WEEK"), "未知步长返回 0，由调用方走不切片的兜底");
        assertEquals(0L, SkywalkingTimes.stepMillis(null));
    }
}
