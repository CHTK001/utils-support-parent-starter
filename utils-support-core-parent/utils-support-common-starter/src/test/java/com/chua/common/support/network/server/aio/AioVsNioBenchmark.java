package com.chua.common.support.network.server.aio;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NIO(Reactor)与 AIO(IOCP/Proactor)HTTP 传输层对比基准。
 *
 * <p>两种实现以完全相同的路由(/bench,固定文本响应)分别压测,
 * 输出 RPS / 平均延迟 / 失败数,用于验证 AIO 在 Windows 高并发下的收益。</p>
 *
 * <p>默认跳过(不拖慢 CI),启用方式:</p>
 * <pre>
 *   mvn test -Dtest=AioVsNioBenchmark -Dbench=true
 * </pre>
 *
 * <p>可调参数:-Dbench.connections=256(并发连接数)、-Dbench.requests=50000(总请求数)。</p>
 *
 * @author CH
 * @since 2026/08/24
 */
@Slf4j
class AioVsNioBenchmark {

    /**
     * 基准路由路径
     */
    private static final String BENCH_PATH = "/bench";

    /**
     * 基准响应体
     */
    private static final String BENCH_BODY = "OK";

    /**
     * 默认并发连接数
     */
    private static final int DEFAULT_CONNECTIONS = 256;

    /**
     * 默认总请求数
     */
    private static final long DEFAULT_REQUESTS = 50_000L;

    /**
     * 预热请求数:触发 JIT 与连接建立,不计入统计
     */
    private static final int WARMUP_REQUESTS = 2_000;

    /**
     * HTTP 200 状态码
     */
    private static final int STATUS_OK = 200;

    @org.junit.jupiter.api.Test
    @EnabledIfSystemProperty(named = "bench", matches = "true")
    void runBenchmark() throws Exception {
        bench("aio");
        bench("nio");
    }

    /**
     * 对指定 SPI 类型执行压测。
     *
     * @param type SPI 类型标识(aio / nio)
     * @throws Exception 压测过程异常
     */
    private void bench(String type) throws Exception {
        int connections = Integer.getInteger("bench.connections", DEFAULT_CONNECTIONS);
        long totalRequests = Long.getLong("bench.requests", DEFAULT_REQUESTS);

        ServerSetting setting = ServerSetting.defaults();
        setting.setPort(0);
        Server server = ServiceProvider.of(Server.class).getNewExtension(type, setting);
        if (!(server instanceof com.chua.common.support.network.server.http.ConfigServer configServer)) {
            log.warn("[{}] 实现不支持 HTTP 路由,跳过", type);
            return;
        }
        configServer.registerMapping(BENCH_PATH, HttpMethod.GET,
                (request, response) -> response.setResult(BENCH_BODY));
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getPort();

        try {
            // 预热:JIT 编译 + 连接池建立
            fire(baseUrl, connections, WARMUP_REQUESTS);
            AtomicLong errors = new AtomicLong();
            long start = System.currentTimeMillis();
            List<Long> latencies = fireCollect(baseUrl, connections, totalRequests, errors);
            long elapsedMillis = Math.max(System.currentTimeMillis() - start, 1);

            double rps = latencies.size() * 1000.0 / elapsedMillis;
            double avgLatency = avgOf(latencies);
            // 测试环境无 SLF4J 绑定,直接走 stdout 保证结果可见
            System.out.printf("[BENCH] type=%s connections=%d requests=%d elapsedMs=%d rps=%.0f avgLatencyMs=%.2f errors=%d%n",
                    type, connections, totalRequests, elapsedMillis,
                    rps, avgLatency, errors.get());
        } finally {
            server.stop();
        }
    }

    /**
     * 并发压测(只统计完成量,不采集延迟明细)。
     *
     * @param baseUrl      服务根地址
     * @param connections  并发连接数
     * @param totalRequests 总请求数
     * @throws Exception 压测过程异常
     */
    private void fire(String baseUrl, int connections, long totalRequests) throws Exception {
        fireCollect(baseUrl, connections, totalRequests, new AtomicLong());
    }

    /**
     * 并发压测并采集延迟明细。
     *
     * <p>每"虚拟线程"持有独立 HttpClient(复用连接),按总量均分请求配额。</p>
     *
     * @param baseUrl       服务根地址
     * @param connections   并发连接数
     * @param totalRequests 总请求数
     * @param errors        错误计数器
     * @return 每请求延迟明细(毫秒)
     */
    private List<Long> fireCollect(String baseUrl, int connections, long totalRequests,
                                   AtomicLong errors) throws Exception {
        List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>());
        long perConnection = Math.max(totalRequests / connections, 1);
        CompletableFuture<?>[] workers = new CompletableFuture[connections];
        for (int i = 0; i < connections; i++) {
            workers[i] = CompletableFuture.runAsync(() -> {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + BENCH_PATH)).GET().build();
                for (long j = 0; j < perConnection; j++) {
                    long start = System.nanoTime();
                    try {
                        HttpResponse<String> resp = client.send(request,
                                HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() != STATUS_OK) {
                            errors.incrementAndGet();
                        }
                        latencies.add((System.nanoTime() - start) / 1_000_000L);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
                client.close();
            });
        }
        CompletableFuture.allOf(workers).join();
        return latencies;
    }

    /**
     * 计算平均延迟。
     *
     * @param latencies 延迟列表
     * @return 平均值(毫秒)
     */
    private double avgOf(List<Long> latencies) {
        if (latencies.isEmpty()) {
            return 0.0;
        }
        long total = 0;
        for (Long latency : latencies) {
            total += latency;
        }
        return (double) total / latencies.size();
    }
}
