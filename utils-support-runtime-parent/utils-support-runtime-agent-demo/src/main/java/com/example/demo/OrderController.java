package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
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

    private static final Logger LOG = LoggerFactory.getLogger(OrderController.class);

    /**
     * 订单计数器
     */
    private static final AtomicLong COUNTER = new AtomicLong(0L);

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
     * <p>尝试连接 192.0.2.1:9999（RFC 5737 TEST-NET-1 地址，肯定不可达，
     * 但 Socket.connect / HttpURLConnection.getResponseCode 仍会被拦截）。</p>
     *
     * <p>需要 Java 启动时通过 {@code -Xbootclasspath/a:} 把 agent jar 放在 bootstrap classloader，
     * 否则插桩代码 NoClassDefFoundError（本演示通过启动脚本的 JVM 参数启用）。</p>
     *
     * @param orderId 订单 ID
     */
    private void notifyExternalService(long orderId) {
        LOG.info("notifyExternalService 被调用，orderId={}", orderId);
        try {
            // 同时发起两个连接：一个 HTTP（HttpURLConnection 拦截），一个纯 Socket（Socket.connect 拦截）
            // 纯 Socket 更容易被 transformer 拦截 —— HttpURLConnection.connect 是 protected abstract
            try {
                java.net.Socket sock = new java.net.Socket();
                sock.connect(new java.net.InetSocketAddress("192.0.2.1", 9999), 200);
                sock.close();
            } catch (Throwable t) {
                LOG.info("Socket 连接异常: {}", t.getClass().getSimpleName());
            }
            // HTTP 备用
            URL url = new URL("http://192.0.2.1:9999/notify?orderId=" + orderId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(200);
            conn.setReadTimeout(200);
            try {
                int code = conn.getResponseCode();
                LOG.info("外部 notify 状态码: {}", code);
            } catch (IOException e) {
                LOG.info("外部 notify IO 异常: {}", e.getMessage());
            } finally {
                conn.disconnect();
            }
        } catch (Throwable e) {
            LOG.warn("外部通知框架异常: {}/{}", e.getClass().getSimpleName(), e.getMessage());
        }
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
