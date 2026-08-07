package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

        // 触发外部 HTTP 连接（即使不可达也会被 TransmissionHandler 拦截）
        notifyExternalService(orderId);

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", "ORDER-" + orderId);
        result.put("amount", amount);
        result.put("status", "CREATED");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    /**
     * 触发外部 HTTP 连接 — 用于演示 TransmissionHandler + SoftwareDetector 识别客户端栈帧。
     *
     * <p>尝试连接 192.0.2.1:9999（RFC 5737 TEST-NET-1 地址，肯定不可达，但 Socket.connect 仍会被拦截）。</p>
     */
    private void notifyExternalService(long orderId) {
        try {
            URL url = new URL("http://192.0.2.1:9999/notify?orderId=" + orderId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            try {
                conn.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            // 连接失败也正常 —— 我们只关心 Socket.connect 是否被拦截
            LOG.debug("外部通知失败: {}", e.getMessage());
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
