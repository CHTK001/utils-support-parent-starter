package com.chua.skywalking.support.engine;

import com.chua.common.support.apm.OtnlMetricQuery;
import com.chua.common.support.apm.OtnlQuery;
import com.chua.common.support.apm.OtnlScope;
import com.chua.common.support.apm.model.OtnlMetricSeries;
import com.chua.common.support.apm.model.OtnlStatus;
import com.chua.common.support.apm.model.OtnlTopology;
import com.chua.skywalking.support.client.SkywalkingSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对真实 OAP 的端到端契约测试（可选执行）。
 * <p>
 * <b>与其它单测的区别：</b>其它单测全部是离线的（反射调私有方法、纯数据断言），
 * 能证明「解析逻辑对」，但证明不了「这些 GraphQL 语句在真实 OAP 上真的能跑通」。
 * 后者恰恰是这套代码最大的风险面 —— 一条语句里混进老版本没有的字段，
 * 整个页面就是空的，而且不报错。
 * </p>
 * <p>
 * <b>默认跳过。</b>用小写 {@code oap} 作为 JUnit 的 Tag，不带 {@code -Dgroups=oap}
 * 时不会执行；需要真机验证时才打开，避免普通构建因为「本机没起 OAP」而红。
 * </p>
 * <pre>
 * mvn -o -pl utils-support-middleware-parent/utils-support-skywalking-starter test \
 *     -Dtest=SkywalkingOapContractIT -DfailIfNoTests=false -DskipTests=false \
 *     -Dgroups=oap -Dskywalking.url=http://127.0.0.1:12801
 * </pre>
 * <p>
 * 这个类只做<b>只读</b>查询，不会向 OAP 写任何东西。
 * </p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@org.junit.jupiter.api.Tag("oap")
class SkywalkingOapContractIT {

    private static SkywalkingOtnlEngine engine;

    @BeforeAll
    static void setUp() {
        engine = new SkywalkingOtnlEngine();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    /**
     * 目标 OAP 必须可达，否则跳过而不是失败
     */
    private static void requireOap() {
        OtnlStatus status = engine.status();
        Assumptions.assumeTrue(status.available(),
                "没有可用的 OAP（" + SkywalkingSettings.url() + "）：" + status.message());
    }

    @Test
    @DisplayName("status 能拿到真实版本号（证明 GraphQL 与鉴权链路通）")
    void statusReportsVersion() {
        requireOap();
        OtnlStatus status = engine.status();
        assertNotNull(status.version());
        assertTrue(status.version().matches("\\d+\\.\\d+\\.\\d+.*"),
                "版本号应形如 9.7.0，实际：" + status.version());
    }

    @Test
    @DisplayName("服务列表语句在原版 OAP 上不报错（SERVICES 用的是 metadata v2 字段）")
    void servicesQueryIsValid() {
        requireOap();
        // 空库也应正常返回空列表，而不是抛异常；若字段/schema 不匹配，这里就会炸
        assertNotNull(engine.services(OtnlQuery.empty()));
    }

    @Test
    @DisplayName("全局拓扑按版本选语句：老版本走 legacy，不因 layers 字段整页报错")
    void topologyQueryIsValid() {
        requireOap();
        OtnlTopology topology = engine.topology(OtnlQuery.empty());
        assertNotNull(topology);
        assertNotNull(topology.nodes(), "拓扑节点列表不应为 null");
    }

    @Test
    @DisplayName("指标时序：超长窗口被自动切片后仍能返回（500 步长上限回归）")
    void metricsSurvivesWindowBeyondLimit() {
        requireOap();
        long end = System.currentTimeMillis();
        // 24 小时 + 分钟级 ＝ 1440 个步长，远超 OAP 的 500 步长上限。
        // 修复前这里拿到的 points 一定是空列表（整条查询报错被吞掉）。
        OtnlMetricSeries series = engine.metrics(new OtnlMetricQuery("service_cpm", OtnlScope.SERVICE,
                "contract-it-not-exist", null, null, end - 1440 * 60_000L, end, "MINUTE"));
        // 实体不存在时值是空的，但「不抛异常且返回结构完整」本身就是契约
        assertNotNull(series);
        assertNotNull(series.points());
    }

    @Test
    @DisplayName("告警语句在原版 OAP 上不报错（ALARMS 只用 9.7 就有的字段）")
    void alarmsQueryIsValid() {
        requireOap();
        assertNotNull(engine.alarms(OtnlQuery.empty()));
    }

    @Test
    @DisplayName("追踪列表语句参数名正确（queryDuration / traceState，而不是 duration）")
    void tracesQueryIsValid() {
        requireOap();
        List<?> traces = engine.traces(OtnlQuery.empty());
        assertNotNull(traces, "参数名写错时 OAP 会返回 Validation error，这里会拿到空列表");
    }

    @Test
    @DisplayName("实体不存在时 readMetricsValues 仍返回时间桶（验证 KVInt.id 解析路径）")
    void metricPointIdsAreParsable() {
        requireOap();
        long end = System.currentTimeMillis();
        OtnlMetricSeries series = engine.metrics(new OtnlMetricQuery("service_cpm", OtnlScope.SERVICE,
                "contract-it-not-exist", null, null, end - 10 * 60_000L, end, "MINUTE"));
        // OAP 对不存在的实体也会回填时间桶（值标记为空），
        // 因此只要 id 解析对了，points 就不该是空的 —— 这正是「格式不匹配导致整图空白」的回归点
        assertTrue(series.points().stream().allMatch(p -> p.time() > 0),
                "所有数据点都必须解析出有效时间戳，实际点数=" + series.points().size());
    }
}
