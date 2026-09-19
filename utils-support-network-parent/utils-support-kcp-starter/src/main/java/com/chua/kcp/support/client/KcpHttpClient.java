package com.chua.kcp.support.client;

import com.chua.common.support.lang.json.Json;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import kcp.Ukcp;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于 kcp-基础 的 KCP HTTP 客户端（简化版）。
 *
 * <p>在 KCP 之上提供 HTTP 风格请求-响应，使用 {@code topic=path} 映射 HTTP path，
 * payload 为 JSON 字符串。响应通过 {@code resp/path} 主题回传。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class KcpHttpClient {

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

    /**
     * 创建 kcphttp客户端 实例
     * @param host 主机
     * @param port int
     * @param port 端口
     */
    public KcpHttpClient(String host, int port) {
        this(host, port, 5000L);
    }

    /**
     * 创建 kcphttp客户端 实例
     * @param host 主机
     * @param port int
     * @param timeoutMs long
     * @param port 端口
     * @param timeoutMs 超时ms
     */
    public KcpHttpClient(String host, int port, long timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 连接
    */
    public void connect() throws Exception {
        client = new KcpClient("kcp-http-client", "kcp://" + host + ":" + port);
        try {
            client.connect();
        } catch (Exception e) {
            throw new Exception("KCP HTTP 客户端连接失败", e);
        }
    }

    /**
     * 请求
     *
     * @param method 方法
     * @param path 路径
     * @param headers 头部
     * @param body 主体
     * @return 请求的结果
     */
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

    /**
     * 获取
     *
     * @param path 路径
     * @return 获取的结果
     */
    public HttpResponse get(String path) throws Exception {
        return request("GET", path, null, null);
    }

    /**
     * Post
     *
     * @param path 路径
     * @param body 主体
     * @return post的结果
     */
    public HttpResponse post(String path, byte[] body) throws Exception {
        return request("POST", path, null, body);
    }

    /**
     * 是否连接
     *
     * @return 是否连接的结果
     */
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /**
     * 关闭
    */
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
     * @author CH
     * @since 4.0.0
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

        /**
         * 获取状态
         *
         * @return 获取状态的结果
         */
        public int getStatus() {
            return status;
        }

        /**
         * 设置状态
         *
         * @param status 状态
         */
        public void setStatus(int status) {
            this.status = status;
        }

        /**
         * 获取头部
         *
         * @return 获取头部的结果
         */
        public Map<String, String> getHeaders() {
            return headers;
        }

        /**
         * 获取头部
         *
         * @param name 名称
         * @return 获取头部的结果
         */
        public String getHeader(String name) {
            return headers.get(name);
        }

        /**
         * 获取主体
         *
         * @return 获取主体的结果
         */
        public byte[] getBody() {
            return body;
        }

        /**
         * 获取主体字符串
         *
         * @return 获取主体字符串的结果
         */
        public String getBodyString() {
            return body != null ? new String(body, StandardCharsets.UTF_8) : "";
        }

        @Override
        /**
         * 转为字符串
        */
        public String toString() {
            return "HttpResponse{status=" + status + ", body=" + getBodyString() + "}";
        }
    }
}
