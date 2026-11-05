// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.skywalking.support.engine;

import com.chua.common.support.apm.OtnlMetricQuery;
import com.chua.common.support.apm.OtnlQuery;
import com.chua.common.support.apm.OtnlScope;
import com.chua.skywalking.support.client.SkywalkingException;
import com.chua.skywalking.support.client.SkywalkingOapClient;
import com.chua.skywalking.support.client.SkywalkingSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 OAP 上的「静默失败」巡检（可选执行，{@code -Dgroups=oap}）。
 * <p>
 * <b>为什么需要这个类：</b>引擎每个读方法都是 {@code try { ... } catch (Exception e) { return empty; }}
 * ——这是对的（某个指标取不到不该让整页 500），但它让「GraphQL 语句写错」和「真的没数据」
 * 变成了同一个返回值：<b>空列表</b>。于是页面上「一条数据都没有」时，无法区分是没数据还是查询挂了。
 * </p>
 * <p>
 * 常规契约测试只断言 {@code assertNotNull(result)}，<b>这种断言抓不到语句错误</b>：
 * 客户端把异常吞了，返回的仍是非 null 的空列表，测试照样绿。
 * </p>
 * <p>
 * 这里的做法：用反射把引擎的 {@link SkywalkingOapClient} 换成一个「记录型」子类，
 * 把每次 RPC 的异常都记下来。跑完所有读方法后，只要有任何一条 {@link SkywalkingException}
 * 就判失败，并把原始 GraphQL 错误信息打印出来 —— 这才是「语句在真机能不能跑」的证据。
 * </p>
 * <p>
 * 只读，不向 OAP 写任何数据。
 * </p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@org.junit.jupiter.api.Tag("oap")
class SkywalkingSilentFailureIT {

    /**
     * 记录型客户端捕获到的失败
     */
    private static final List<String> FAILURES = new CopyOnWriteArrayList<>();

    private static SkywalkingOtnlEngine engine;

    @BeforeAll
    static void setUp() throws Exception {
        engine = new SkywalkingOtnlEngine();
        SkywalkingOapClient recorder = new SkywalkingOapClient() {
            @Override
            public com.fasterxml.jackson.databind.JsonNode data(String query, Map<String, Object> variables) {
                try {
                    return super.data(query, variables);
                } catch (SkywalkingException e) {
                    FAILURES.add(describe(query) + " -> " + e.getMessage());
                    throw e;
                }
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode data(String query) {
                try {
                    return super.data(query);
                } catch (SkywalkingException e) {
                    FAILURES.add(describe(query) + " -> " + e.getMessage());
                    throw e;
                }
            }
        };
        Field field = SkywalkingOtnlEngine.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(engine, recorder);
    }

    @AfterAll
    static void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * 取 GraphQL 语句的操作名，便于定位是哪条查询失败
     */
    private static String describe(String query) {
        String flat = query.replaceAll("\\s+", " ").trim();
        int brace = flat.indexOf('{');
        return brace > 0 ? flat.substring(0, brace).trim() : flat.substring(0, Math.min(60, flat.length()));
    }

    /**
     * 目标必须是真实 OAP：假 OAP 对任何查询都返回 200，不会产生失败记录，
     * 「零失败」就没有说服力
     */
    private static boolean realOap() {
        var status = engine.status();
        return status.available() && status.version() != null
                && status.version().matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    @DisplayName("记录器自检：故意发一条坏语句，必须被捕获")
    void recorderItselfWorks() {
        Assumptions.assumeTrue(realOap(), "没有可用的真实 OAP（" + SkywalkingSettings.url() + "），跳过");

        int before = FAILURES.size();
        try {
            // OAP 上不存在 thisFieldDoesNotExist，必然报 FieldUndefined
            engineClient().data("query { thisFieldDoesNotExist }");
        } catch (Exception ignore) {
            // 预期异常，引擎侧通常吞掉，这里直接调用所以会抛
        }
        assertTrue(FAILURES.size() > before,
                "记录器没有捕获到错误，说明「零失败」这个结论不可信");
        // 清掉自检产生的记录，避免污染断言
        FAILURES.subList(before, FAILURES.size()).clear();
    }

    /**
     * 取出被替换后的记录型客户端
     */
    private static SkywalkingOapClient engineClient() {
        try {
            Field field = SkywalkingOtnlEngine.class.getDeclaredField("client");
            field.setAccessible(true);
            return (SkywalkingOapClient) field.get(engine);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("全部读方法在真实 OAP 上不产生任何 GraphQL 错误")
    void noSilentFailures() {
        Assumptions.assumeTrue(realOap(), "没有可用的真实 OAP（" + SkywalkingSettings.url() + "），跳过");

        OtnlQuery q = OtnlQuery.empty();
        // 逐个调用：异常已在引擎内被吞，这里靠 recorder 捕获
        engine.services(q);
        engine.instances("silent-failure-probe", q);
        engine.endpoints("silent-failure-probe", q);
        engine.traces(q);
        engine.topology(q);
        engine.topology(q.withService("silent-failure-probe"));
        engine.alarms(q);
        engine.logs(q);
        engine.health(q);
        engine.profileTasks(q);
        engine.metrics(new OtnlMetricQuery("service_cpm", OtnlScope.SERVICE,
                "silent-failure-probe", null, null, 0, 0, "MINUTE"));
        engine.metrics(new OtnlMetricQuery("service_resp_time", OtnlScope.SERVICE_INSTANCE,
                "silent-failure-probe", "instance-1", null, 0, 0, "MINUTE"));
        engine.metrics(new OtnlMetricQuery("endpoint_cpm", OtnlScope.ENDPOINT,
                "silent-failure-probe", null, "/probe", 0, 0, "MINUTE"));

        assertTrue(FAILURES.isEmpty(),
                "以下查询在真实 OAP 上失败（页面会表现为「没有数据」而不是报错）：\n"
                        + String.join("\n", FAILURES));
    }
}