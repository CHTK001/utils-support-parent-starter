package perf;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.common.support.network.server.filter.ChannelQueueServerFilter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * ChannelQueueServerFilter 功能冒烟探针（诊断增强版）：进程内自起 AIO HTTP 服务器并挂载
 * 通道排队过滤器，用真实 HTTP 请求断言「放行 / 限流 429 / 排队超时 503 / 响应头」行为。
 *
 * <p>本版对每个请求输出 <b>状态码 + 完成耗时 + 响应体 + 排队头</b>，并输出过滤器统计，
 * 用于定位「排队超时路径未按预期返回 503」的问题。</p>
 *
 * <p><b>运行约束：</b>运行前必须经人工批准；单进程回环（server 与 client 同进程），
 * 不依赖、也不启动项目的任何前端/后端服务；全程 &lt; 90 秒。</p>
 *
 * <p>编译与运行（在 utils-support-parent-starter 目录下，PowerShell）：</p>
 * <pre>
 *   $cp = Get-Content test/perf/cp.txt -Raw
 *   $svc = "utils-support-core-parent/utils-support-common-starter/target/classes"
 *   javac -encoding UTF-8 -cp "$cp;$svc" -d test/perf/classes test/perf/ChannelQueueSmokeProbe.java
 *   java -cp "$cp;$svc;test/perf/classes" perf.ChannelQueueSmokeProbe
 * </pre>
 *
 * @author CH
 * @since 4.0.0.54
 */
public final class ChannelQueueSmokeProbe {

    /**
     * 工具类不允许实例化
     */
    private ChannelQueueSmokeProbe() {
    }

    /**
     * 单个请求的观测结果。
     *
     * @param code       HTTP 状态码
     * @param elapsedMs  从批量发出到完成的耗时
     * @param body       响应体（截断）
     * @param queueState X-Channel-Queue 头
     * @param retryAfter Retry-After 头
     */
    private record Result(int code, long elapsedMs, String body, String queueState, String retryAfter) {
    }

    /**
     * 入口：依次跑两个场景，任一断言失败即以非 0 退出。
     *
     * @param args 无参
     * @throws Exception 服务启动失败
     */
    public static void main(String[] args) throws Exception {
        int failures = 0;
        failures += scenarioLimitAndReject();
        failures += scenarioQueueTimeout();
        System.out.println(failures == 0 ? "==== SMOKE PASS ====" : "==== SMOKE FAIL failures=" + failures + " ====");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * 场景 1：1 通道、每秒 5 次、队列 3、超时 2s。并发 20 个请求：
     * 部分直接放行 200，队列满的请求应被 429 拒绝且携带 queue_full 头。
     *
     * @return 失败数（0=通过）
     * @throws Exception 请求失败
     */
    private static int scenarioLimitAndReject() throws Exception {
        ChannelQueueServerFilter filter = new ChannelQueueServerFilter(1, 5, 3, 2000);
        AbstractServer server = start(filter);
        try {
            HttpClient client = client();
            URI uri = base(server.getPort());
            HttpResponse<String> first = client.send(req(uri), HttpResponse.BodyHandlers.ofString());
            System.out.println("s1 single: code=" + first.statusCode() + " body=" + first.body());

            List<CompletableFuture<Result>> futures = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                futures.add(client.sendAsync(req(uri), HttpResponse.BodyHandlers.ofString())
                        .thenApply(r -> toResult(r, t0)));
            }
            int ok = 0;
            int full = 0;
            int other = 0;
            for (int i = 0; i < futures.size(); i++) {
                Result r = futures.get(i).join();
                if (r.code() == 200) {
                    ok++;
                } else if (r.code() == 429) {
                    full++;
                } else {
                    other++;
                }
                System.out.printf("s1 req%02d code=%d elapsed=%dms body=%s state=%s%n",
                        i, r.code(), r.elapsedMs(), r.body(), r.queueState());
            }
            System.out.printf("s1 summary ok=%d full429=%d other=%d queuedNow=%d accepted=%d rejected=%d filter=%s%n",
                    ok, full, other, filter.getQueuedRequests(), filter.getAcceptedTotal(),
                    filter.getRejectedTotal(), filter);
            boolean pass = ok >= 1 && full >= 1 && other == 0;
            System.out.println("s1 -> " + (pass ? "PASS" : "FAIL"));
            return pass ? 0 : 1;
        } finally {
            server.stop();
        }
    }

    /**
     * 场景 2：1 通道、每秒 1 次、队列 2、排队超时 200ms。精确时序：
     * 首请求同步发送消耗当前令牌（200）；隔 100ms 后并发 3 个——前 2 个入队
     * （下枚令牌在 1s 后，必然超过 200ms 排队期限 → 503 queue_timeout），
     * 第 3 个队列已满 → 429 queue_full。
     *
     * @return 失败数（0=通过）
     * @throws Exception 请求失败
     */
    private static int scenarioQueueTimeout() throws Exception {
        ChannelQueueServerFilter filter = new ChannelQueueServerFilter(1, 1, 2, 200);
        AbstractServer server = start(filter);
        try {
            HttpClient client = client();
            URI uri = base(server.getPort());
            HttpResponse<String> first = client.send(req(uri), HttpResponse.BodyHandlers.ofString());
            System.out.println("s2 first: code=" + first.statusCode() + " body=" + first.body());
            Thread.sleep(100);
            List<CompletableFuture<Result>> futures = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < 3; i++) {
                futures.add(client.sendAsync(req(uri), HttpResponse.BodyHandlers.ofString())
                        .thenApply(r -> toResult(r, t0)));
            }
            int ok = 0;
            int full = 0;
            int timeout = 0;
            int other = 0;
            for (int i = 0; i < futures.size(); i++) {
                Result r = futures.get(i).join();
                if (r.code() == 200) {
                    ok++;
                } else if (r.code() == 429) {
                    full++;
                } else if (r.code() == 503) {
                    timeout++;
                } else {
                    other++;
                }
                System.out.printf("s2 req%d code=%d elapsed=%dms body=%s state=%s retryAfter=%s%n",
                        i, r.code(), r.elapsedMs(), r.body(), r.queueState(), r.retryAfter());
            }
            System.out.printf("s2 summary ok=%d full429=%d timeout503=%d other=%d queuedNow=%d "
                            + "accepted=%d rejected=%d filter=%s%n",
                    ok, full, timeout, other, filter.getQueuedRequests(), filter.getAcceptedTotal(),
                    filter.getRejectedTotal(), filter);
            boolean pass = first.statusCode() == 200 && timeout >= 1 && full >= 1 && other == 0;
            System.out.println("s2 -> " + (pass ? "PASS" : "FAIL"));
            return pass ? 0 : 1;
        } finally {
            server.stop();
        }
    }

    /**
     * 把 HTTP 响应转成观测记录。
     *
     * @param r  HTTP 响应
     * @param t0 批量发出时刻（nanoTime）
     * @return 观测记录
     */
    private static Result toResult(HttpResponse<String> r, long t0) {
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        String body = r.body();
        if (body != null && body.length() > 60) {
            body = body.substring(0, 60) + "...";
        }
        return new Result(r.statusCode(), elapsed, body,
                r.headers().firstValue("X-Channel-Queue").orElse(null),
                r.headers().firstValue("Retry-After").orElse(null));
    }

    /**
     * 启动挂载给定过滤器的 AIO 服务器（随机端口）。
     *
     * @param filter 通道排队过滤器
     * @return 已启动的服务器
     */
    private static AbstractServer start(ChannelQueueServerFilter filter) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(0);
        AbstractServer server = new AioHttpServer(setting);
        server.addReactiveFilter(filter);
        server.registerMapping("/ping", HttpMethod.GET, (req, resp) -> resp.setResult("OK"));
        server.start();
        return server;
    }

    /**
     * 构建回环 base URI。
     *
     * @param port 服务器端口
     * @return URI
     */
    private static URI base(int port) {
        return URI.create("http://127.0.0.1:" + port + "/ping");
    }

    /**
     * 构建 GET 请求。
     *
     * @param uri 目标地址
     * @return 请求
     */
    private static HttpRequest req(URI uri) {
        return HttpRequest.newBuilder().uri(uri).GET().build();
    }

    /**
     * 构建 HTTP/1.1 客户端（避免 h2c 升级握手干扰）。
     *
     * @return 客户端
     */
    private static HttpClient client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}
