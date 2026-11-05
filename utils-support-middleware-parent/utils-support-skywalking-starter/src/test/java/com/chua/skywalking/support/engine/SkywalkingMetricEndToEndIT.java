package com.chua.skywalking.support.engine;

import com.chua.common.support.apm.OtnlMetricQuery;
import com.chua.common.support.apm.OtnlScope;
import com.chua.common.support.apm.model.OtnlMetricSeries;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端回归：前端最常用的两个组合，在真实 OAP 上必须能拿到数据点。
 * <p>
 * 只读、可选执行（{@code -Dgroups=oap}）。这两条用例覆盖了本轮修的两个真实缺陷：
 * </p>
 * <ul>
 *   <li><b>500 步长上限</b>：24 小时 + 分钟级 ＝ 1440 个步长，不切片整条查询报错；</li>
 *   <li><b>KVInt.id 格式</b>：解析不出来时每个点都被丢弃，表现为整图空白。</li>
 * </ul>
 * <p>
 * 因此断言不只看「不抛异常」，而是要求<b>真的解析出了时间点</b>——
 * 若 id 格式回归，这个断言会立刻失败。
 * </p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@org.junit.jupiter.api.Tag("oap")
class SkywalkingMetricEndToEndIT {

    private final SkywalkingOtnlEngine engine = new SkywalkingOtnlEngine();

    /**
     * 目标必须是<b>真实</b> OAP
     * <p>
     * 只判断 {@code available} 不够：本地 12800 上曾经跑过一个假 OAP（MockOap），
     * 它对任何查询都返回数据，于是「点数 &gt; 0」这种断言在假服务上也会过 —— 那就不是验证了。
     * 真 OAP 的 {@code version} 形如 {@code 9.7.0-066457b}，假服务给不出来。
     * </p>
     */
    private void requireRealOap() {
        var status = engine.status();
        Assumptions.assumeTrue(status.available(), "没有可用的 OAP，跳过");
        Assumptions.assumeTrue(status.version() != null && status.version().matches("\\d+\\.\\d+\\.\\d+.*"),
                "目标不是真实 OAP（version=" + status.version() + "），跳过");
    }

    @Test
    @DisplayName("最近 24 小时 + 分钟级：切片后仍解析出数据点（不是空列表）")
    void dayWindowWithMinuteStepYieldsPoints() {
        requireRealOap();

        long end = System.currentTimeMillis();
        OtnlMetricSeries series = engine.metrics(new OtnlMetricQuery("service_cpm", OtnlScope.SERVICE,
                "e2e-not-exist", null, null, end - 1440 * 60_000L, end, "MINUTE"));

        assertNotNull(series);
        // OAP 对不存在的实体也会回填时间桶（值标记为空），所以「有数据点」即证明
        // 1) 查询没被 500 步长上限拒掉；2) KVInt.id 被正确解析成时间戳
        assertTrue(series.points().size() > 400,
                "1440 分钟窗口应回填数百个时间点，实际=" + series.points().size()
                        + "；点数为 0 说明查询被拒或 id 解析失败");
        assertTrue(series.points().stream().allMatch(p -> p.time() > 0),
                "每个点都必须有有效时间戳");
        // 时间必须落在窗口内，证明切片后没有把时间轴算错
        long from = end - 1440 * 60_000L - 60_000L;
        assertTrue(series.points().stream().allMatch(p -> p.time() >= from - 3_600_000L && p.time() <= end + 3_600_000L),
                "数据点时间应在查询窗口内");
    }

    @Test
    @DisplayName("最近 1 小时 + 分钟级：单段查询也能解析出数据点")
    void hourWindowYieldsPoints() {
        requireRealOap();

        long end = System.currentTimeMillis();
        OtnlMetricSeries series = engine.metrics(new OtnlMetricQuery("service_cpm", OtnlScope.SERVICE,
                "e2e-not-exist", null, null, end - 60 * 60_000L, end, "MINUTE"));

        assertTrue(series.points().size() > 40,
                "60 分钟窗口应回填数十个时间点，实际=" + series.points().size());
    }
}
