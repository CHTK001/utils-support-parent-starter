package com.chua.prometheus.support.example;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.prometheus.support.client.PrometheusClient;
import com.chua.prometheus.support.engine.PrometheusEngine;
import com.chua.prometheus.support.model.PrometheusMetric;
import com.chua.prometheus.support.model.QueryResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Prometheus 综合示例 — 基于 PrometheusClient 与 Engine SPI, 支持自检。
 *
 * <h2>用法</h2>
 * <pre>
 *   # 默认地址 http://localhost:9090, 自检 up 查询
 *   java PrometheusExample
 *
 *   # 指定地址
 *   java PrometheusExample --url http://192.168.1.5:9090
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PrometheusExample {

    /**
     * 程序退出码: 成功
     */
    private static final int EXIT_CODE_SUCCESS = 0;

    /**
     * 程序退出码: 失败
     */
    private static final int EXIT_CODE_FAILURE = 1;

    /**
     * 默认 Prometheus 地址
     */
    private static final String DEFAULT_URL = "http://localhost:9090";

    public static void main(String[] args) {
        String url = DEFAULT_URL;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--url".equals(args[i])) {
                url = args[i + 1];
            }
        }

        PrometheusExample example = new PrometheusExample();
        boolean passed = example.runTest(url);
        System.exit(passed ? EXIT_CODE_SUCCESS : EXIT_CODE_FAILURE);
    }

    /**
     * 自检入口
     *
     * @param url Prometheus 地址
     * @return 是否全部通过
     */
    public boolean runTest(String url) {
        log.info("===== PrometheusExample --test [url={}] =====", url);
        boolean allPassed = true;
        allPassed &= testClientQuery(url);
        allPassed &= testEngineSpi(url);
        return allPassed;
    }

    /**
     * 测试客户端即时查询
     *
     * @param url 地址
     * @return 是否通过
     */
    public boolean testClientQuery(String url) {
        try {
            PrometheusClient client = PrometheusClient.builder()
                    .baseUrl(url)
                    .timeoutMs(5_000)
                    .build();

            QueryResult result = client.query("up").execute();
            boolean passed = result.hasData();
            for (PrometheusMetric m : result.getResult()) {
                log.info("指标: {} value={} labels={}", m.getName(), m.getValue(), m.getMetric());
            }
            printResult("Client.query(up)", passed);
            client.close();
            return passed;
        } catch (Exception e) {
            printResult("Client.query(up)", false);
            log.warn("查询失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 测试 Engine SPI 加载
     *
     * @param url 地址
     * @return 是否通过
     */
    public boolean testEngineSpi(String url) {
        try {
            Engine engine = Engine.create("prometheus");
            if (!(engine instanceof PrometheusEngine prometheusEngine)) {
                printResult("Engine.create(prometheus)", false);
                return false;
            }
            prometheusEngine.addDataSource("default", url);
            QueryResult result = prometheusEngine.query("up");
            boolean passed = result.hasData();
            printResult("Engine.query(up)", passed);
            prometheusEngine.close();
            return passed;
        } catch (Exception e) {
            printResult("Engine.query(up)", false);
            log.warn("Engine 查询失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 打印自检结果
     *
     * @param name   用例名
     * @param passed 是否通过
     */
    private void printResult(String name, boolean passed) {
        System.out.println((passed ? "[PASS]" : "[FAIL]") + " " + name);
    }
}