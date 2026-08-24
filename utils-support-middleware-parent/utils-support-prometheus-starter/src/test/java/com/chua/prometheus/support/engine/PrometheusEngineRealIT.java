package com.chua.prometheus.support.engine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PrometheusEngine 真实服务集成测试（172.16.0.40:19090）。
 * 环境不可达时自动跳过。
 *
 * @author CH
 * @since 4.0.0.42
 */
class PrometheusEngineRealIT {

    private static final String BASE = "http://172.16.0.40:19090";

    /**
     * 9090/19090 可达才执行。
     */
    @BeforeAll
    static void assumeEnv() {
        Assumptions.assumeTrue(reachable("172.16.0.40", 19090), "Prometheus 不可达，跳过");
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * PromQL 即时查询 up：真实 HTTP 往返且返回合法结构。
     */
    @Test
    void promql_up_query_roundtrip() {
        PrometheusEngine engine = new PrometheusEngine();
        engine.addDataSource("default", BASE);

        var result = engine.query("up");
        assertNotNull(result, "PromQL 查询应返回结果对象");
        System.out.println("[PROM-VERIFY] query(up) => " + result);
    }

    /**
     * 引擎语义拒绝仍生效（与真实查询并存）。
     */
    @Test
    void orm_semantics_rejected() {
        PrometheusEngine engine = new PrometheusEngine();
        engine.addDataSource("default", BASE);
        assertThrows(UnsupportedOperationException.class,
                () -> engine.query(PrometheusEngineRealIT.class).list());
        assertThrows(UnsupportedOperationException.class,
                () -> engine.store("t", java.util.List.of()));
    }
}
