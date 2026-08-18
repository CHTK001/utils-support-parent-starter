package com.chua.kcp.support.client;

import com.chua.common.support.lang.json.Json;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import kcp.Ukcp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于 kcp-base 的 KCP HTTP 客户端（简化版）。
 *
 * <p>在 KCP 之上提供 HTTP 风格请求-响应，使用 {@code topic=path} 映射 HTTP path，
 * payload 为 JSON 字符串。响应通过 {@code resp/path} 主题回传。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KcpHttpClient {

    private static final Logger log = LoggerFactory.getLogger(KcpHttpClient.class);

    /**
     * KCP 服务器主机地址
     */
    private final String host;
    /**
     * KCP 服务器端口号
     */
    private final int port;
    /**
     * 请求超时时间（毫秒）
     */
    private final long timeoutMs;
    /**
     * 底层 KCP 客户端实例
     */
    private KcpClient client;

    public KcpHttpClient(String host, int port) {
        this(host, port, 5000L);
    }

    public KcpHttpClient(String host, int port, long timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    public void connect() throws Exception {
        client = new KcpClient("kcp-http-client", "kcp://" + host + ":" + port);
        try {
            client.connect();
        } catch (Exception e) {
            throw new Exception("KCP HTTP 客户端连接失败", e);
        }
    }

    public HttpResponse request(String method, String path, Map<String, String> headers, byte[] body) throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("KCP HTTP 客户端未连接");
        }
        Map<String, Object> req = new HashMap<>();
        req.put("method", method);
        req.put("path", path);
        req.put("headers", headers != null ? headers : Collections.emptyMap());
        req.put("body", body != null ? new String(body, StandardCharsets.UTF_8) : "");
        String respStr;
        try {
            respStr = client.execute("http", Json.toJson(req), timeoutMs);
        } catch (Exception e) {
            throw new Exception("KCP HTTP 请求失败", e);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> respMap = Json.fromJson(respStr, Map.class);
        HttpResponse resp = new HttpResponse();
        Object status = respMap != null ? respMap.get("status") : null;
        resp.setStatus(status instanceof Number ? ((Number) status).intValue() : 200);
        Object hs = respMap != null ? respMap.get("headers") : null;
        if (hs instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) hs).entrySet()) {
                resp.headers.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        Object b = respMap != null ? respMap.get("body") : null;
        if (b != null) {
            resp.body = String.valueOf(b).getBytes(StandardCharsets.UTF_8);
        }
        return resp;
    }

    public HttpResponse get(String path) throws Exception {
        return request("GET", path, null, null);
    }

    public HttpResponse post(String path, byte[] body) throws Exception {
        return request("POST", path, null, body);
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public void close() {
        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception e) {
                log.warn("KCP HTTP 客户端关闭异常: {}", e.getMessage());
            }
            client = null;
        }
    }

    /**
     * 简易 HTTP 响应对象。
     */
    public static class HttpResponse {
        /**
         * HTTP 状态码，默认 200
         */
        private int status = 200;
        /**
         * 响应头键值映射
         */
        private final Map<String, String> headers = new HashMap<>();
        /**
         * 响应体字节数组，默认空数组
         */
        private byte[] body = new byte[0];

        public int getStatus() {
            return status;
        }

        public void setStatus(int status) {
            this.status = status;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public String getHeader(String name) {
            return headers.get(name);
        }

        public byte[] getBody() {
            return body;
        }

        public String getBodyString() {
            return body != null ? new String(body, StandardCharsets.UTF_8) : "";
        }

        @Override
        public String toString() {
            return "HttpResponse{status=" + status + ", body=" + getBodyString() + "}";
        }
    }
}
