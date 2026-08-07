package com.chua.runtime.e2e;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Boot 测试控制器 — 提供测试端点用于触发各 APM Handler 的数据收集。
 *
 * <p>端点：</p>
 * <ul>
 *   <li>GET /test/log — 记录日志（验证 LogHandler）</li>
 *   <li>GET /test/file — 创建临时文件（验证 FileHandler）</li>
 *   <li>GET /test/http — 发起 HTTP 请求（验证 NetHandler + TransmissionHandler）</li>
 *   <li>GET /test/leak — 故意泄漏 FileInputStream（验证 HandleLeakHandler）</li>
 *   <li>GET /test/trace — 调用业务方法（验证 TraceHandler + DependencyGraphHandler）</li>
 *   <li>GET /test/echo — 健康检查</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@RestController
public class TestController {

    private static final Logger LOG = LoggerFactory.getLogger(TestController.class);
    private static final AtomicLong ORDER_COUNTER = new AtomicLong(0L);
    private static final List<FileInputStream> LEAKED_STREAMS = new CopyOnWriteArrayList<>();

    @GetMapping("/test/log")
    public Map<String, Object> log(@RequestParam(defaultValue = "test") String message) {
        LOG.info("Spring 测试日志: {}", message);
        LOG.debug("Spring 测试 DEBUG: {}", message);
        LOG.error("Spring 测试 ERROR: {}", message);
        Map<String, Object> result = new HashMap<>();
        result.put("status", "logged");
        result.put("message", message);
        return result;
    }

    @GetMapping("/test/file")
    public Map<String, Object> file() {
        try {
            java.io.File tmp = java.io.File.createTempFile("test-", ".tmp");
            try (FileWriter fw = new FileWriter(tmp)) {
                fw.write("test content " + System.currentTimeMillis());
            }
            Map<String, Object> result = new HashMap<>();
            result.put("status", "written");
            result.put("path", tmp.getAbsolutePath());
            return result;
        } catch (IOException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "error");
            result.put("message", e.getMessage());
            return result;
        }
    }

    @GetMapping("/test/http")
    public Map<String, Object> http(@RequestParam(defaultValue = "0") int port) {
        String target = "http://localhost:" + (port > 0 ? port : System.getProperty("local.server.port", "8080")) + "/test/echo";
        try {
            URL url = new URL(target);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            Map<String, Object> result = new HashMap<>();
            result.put("status", "connected");
            result.put("code", code);
            result.put("target", target);
            return result;
        } catch (IOException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "error");
            result.put("message", e.getMessage());
            return result;
        }
    }

    @GetMapping("/test/leak")
    public Map<String, Object> leak() {
        try {
            java.io.File tmp = java.io.File.createTempFile("leak-", ".tmp");
            try (FileWriter fw = new FileWriter(tmp)) {
                fw.write("leak=" + System.currentTimeMillis());
            }
            FileInputStream fis = new FileInputStream(tmp);
            LEAKED_STREAMS.add(fis);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "leaked");
            result.put("count", LEAKED_STREAMS.size());
            return result;
        } catch (IOException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "error");
            result.put("message", e.getMessage());
            return result;
        }
    }

    @GetMapping("/test/trace")
    public Map<String, Object> trace(@RequestParam(defaultValue = "100") long amount) {
        long orderId = ORDER_COUNTER.incrementAndGet();
        LOG.info("创建订单 orderId={} amount={}", orderId, amount);
        validateOrder(amount);
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", "ORDER-" + orderId);
        result.put("amount", amount);
        return result;
    }

    public void validateOrder(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("金额必须大于 0");
        }
        LOG.info("订单校验通过 amount={}", amount);
    }

    @GetMapping("/test/echo")
    public Map<String, String> echo() {
        Map<String, String> result = new HashMap<>();
        result.put("status", "echo");
        return result;
    }

    /**
     * 清理泄漏的流。
     */
    static void clearLeaks() {
        for (FileInputStream fis : LEAKED_STREAMS) {
            try {
                fis.close();
            } catch (IOException ignored) {
            }
        }
        LEAKED_STREAMS.clear();
    }

    static List<FileInputStream> getLeaks() {
        return LEAKED_STREAMS;
    }
}