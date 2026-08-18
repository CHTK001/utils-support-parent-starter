package com.example.demo;

import com.chua.runtime.protocol.TraceContextPropagator;
import com.chua.runtime.protocol.W3CTraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单控制器 — 演示业务方法被 RuntimeSpy 拦截。
 *
 * <p>访问 /order/create?amount=100 将依次触发：</p>
 * <ol>
 *   <li>Spring MVC 入口（被 TraceHandler 拦截产生 SERVER Span）</li>
 *   <li>OrderService.createOrder()（被 TraceHandler 拦截产生内部 Span）</li>
 *   <li>Socket.accept()（被 TransmissionHandler 拦截产生 SERVER accept 记录）</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@RestController
public class OrderController {

    /**
     * LOG
     */
    private static final Logger LOG = LoggerFactory.getLogger(OrderController.class);

    /**
     * 订单计数器
     */
    private static final AtomicLong COUNTER = new AtomicLong(0L);

    /**
     * 故意泄漏的 FileInputStream 列表 — 演示 HandleLeakHandler 检测。
     *
     * <p>每次 /order/create 故意创建一个 FileInputStream 不关闭，
     * 1 秒后即可在 /agent/leaks 看到记录。</p>
     */
    private static final java.util.List<java.io.FileInputStream> LEAKED_STREAMS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * 创建订单 — GET /order/create?amount=100。
     *
     * @param amount 金额（默认 100）
     * @return 订单结果
     */
    @GetMapping("/order/create")
    public Map<String, Object> createOrder(
            @RequestParam(defaultValue = "100") long amount) {
        long orderId = COUNTER.incrementAndGet();
        LOG.info("创建订单 amount={} orderId={}", amount, orderId);

        // 主动发起外部 HTTP 连接，触发 TransmissionHandler + Socket 字节码插桩
        notifyExternalService(orderId);

        // 故意泄漏一个 FileInputStream（演示 HandleLeakHandler）
        try {
            java.io.File tmp = java.io.File.createTempFile("order-", ".log");
            try (java.io.FileWriter fw = new java.io.FileWriter(tmp)) {
                fw.write("order=" + orderId + "\n");
            }
            java.io.FileInputStream fis = new java.io.FileInputStream(tmp);
            LEAKED_STREAMS.add(fis);
            if (LEAKED_STREAMS.size() > 50) {
                java.io.FileInputStream old = LEAKED_STREAMS.remove(0);
                try {
                    old.close();
                } catch (java.io.IOException ignored) {
                }
            }
            LOG.info("故意泄漏 FileInputStream id={}, 累积泄漏数={}", orderId, LEAKED_STREAMS.size());
        } catch (java.io.IOException e) {
            LOG.warn("构造泄漏句柄失败: {}", e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", "ORDER-" + orderId);
        result.put("amount", amount);
        result.put("status", "CREATED");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    /**
     * 触发外部 HTTP 连接 — 用于验证 TransmissionHandler + SoftwareDetector 识别客户端栈帧。
     *
     * <p>同时演示 W3C traceparent 注入：</p>
     * <ul>
     *   <li>通过 {@link TraceContextPropagator#inject(Map)} 生成当前追踪上下文的 traceparent</li>
     *   <li>作为自定义 header（{@code X-Traceparent}）写入 HttpURLConnection</li>
     * </ul>
     *
     * @param orderId 订单 ID
     */
    private void notifyExternalService(long orderId) {
        LOG.info("notifyExternalService 被调用，orderId={}", orderId);
        try {
            URL url = new URL("http://localhost:" + System.getProperty("server.port", "8580") + "/ping?orderId=" + orderId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);

            // W3C traceparent 注入（写到自定义 header，避免污染标准 header）
            Map<String, String> headers = TraceContextPropagator.newOutgoingHeaders();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
            // 同时写到 X-Traceparent 便于本地观察
            String traceparent = headers.get(W3CTraceContext.HEADER_TRACEPARENT);
            if (traceparent != null) {
                conn.setRequestProperty("X-Traceparent", traceparent);
            }

            try {
                int code = conn.getResponseCode();
                LOG.info("外部 notify 状态码: {} traceparent={}", code, traceparent);
            } catch (IOException e) {
                LOG.info("外部 notify IO 异常: {} traceparent={}", e.getMessage(), traceparent);
            } finally {
                conn.disconnect();
            }
        } catch (Throwable e) {
            LOG.warn("外部通知框架异常: {}/{}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 接收 W3C traceparent — 演示 Extract。
     *
     * <p>GET /trace/inbound，携带 {@code traceparent} header 后，
     * 调用 {@link TraceContextPropagator#extract(Map)} 把上游 traceId 应用到当前线程追踪栈，
     * 后续 RuntimeSpy 拦截的 span 自动作为上游 span 的子 Span。</p>
     *
     * @param traceparent 上游传入的 traceparent header（可空）
     * @return 回显当前追踪上下文
     */
    @GetMapping("/trace/inbound")
    public Map<String, Object> inbound(@RequestHeader(value = "traceparent", required = false) String traceparent) {
        Map<String, String> headers = new HashMap<>();
        if (traceparent != null && !traceparent.isEmpty()) {
            headers.put(W3CTraceContext.HEADER_TRACEPARENT, traceparent);
        }
        boolean restored = TraceContextPropagator.extract(headers);
        Map<String, Object> result = new HashMap<>();
        result.put("restored", restored);
        result.put("receivedTraceparent", traceparent);
        result.put("currentTraceId", com.chua.runtime.spy.RuntimeSpy.getCurrentTraceId());
        result.put("currentSpanId", com.chua.runtime.spy.RuntimeSpy.getCurrentSpanId());
        return result;
    }

    /**
     * 简单健康检查。
     *
     * @return pong
     */
    @GetMapping("/ping")
    public Map<String, String> ping() {
        Map<String, String> result = new HashMap<>();
        result.put("status", "pong");
        return result;
    }
}
