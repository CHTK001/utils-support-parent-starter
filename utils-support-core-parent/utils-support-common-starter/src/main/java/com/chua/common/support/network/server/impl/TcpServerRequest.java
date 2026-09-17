package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.request.ServerRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将 TCP 帧中的 HTTP 请求体包装为 {@link ServerRequest}。
 *
 * <p>协议约定：TCP 帧体（不含 4 字节长度头）必须是一个完整的 HTTP/1.x 请求报文，
 * 以 {@code \r\n\r\n}（无 body）或 {@code \r\n\r\n<data>}（有 body）结尾。
 * 头部解析失败时降级为 {@code POST /} ，允许纯字节流场景仍能写入响应。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // JdkTcpServer 接入 UrlMappingServerFilter 后自动使用
 * JdkTcpServer server = new JdkTcpServer(setting)
 *         .registerMapping("/hello", req -> req.getResponse()
 *                 .setBody("hello tcp")
 *                 .end());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
*/
public class TcpServerRequest implements ServerRequest {

    /** 请求方法 */
    private HttpMethod method;
    /** 完整请求 URI（含查询串） */
    private String uri;
    /** 请求路径（不含查询串） */
    private String path;
    /** 查询参数 */
    private Map<String, String> params;
    /** 请求头（大小写不敏感） */
    private Map<String, String> headers;
    /** 请求体字节数组 */
    private byte[] body;
    /** 请求体字符串缓存 */
    private String bodyString;
    /** 客户端地址 */
    private final String remoteAddress;
    /** 客户端端口 */
    private final int remotePort;
    /** 请求属性，用于 Filter 间传递数据 */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    /** 字符集（从 Content-Type 解析，默认 UTF-8） */
    private final Charset charset;

    /**
    * 构造 TCP 帧请求。
    *
    * @param body        帧体字节（完整的 HTTP 请求报文）
    * @param remoteAddr  客户端 InetSocketAddress
    * @param charset     请求体字符集
    */
    public TcpServerRequest(byte[] body, InetSocketAddress remoteAddr, Charset charset) {
        this.body = body != null ? body : new byte[0];
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
        parse();
        if (remoteAddr != null) {
            this.remoteAddress = remoteAddr.getAddress().getHostAddress();
            this.remotePort = remoteAddr.getPort();
        } else {
            this.remoteAddress = "";
            this.remotePort = 0;
        }
    }

    private void parse() {
        String text = new String(body, charset);
        int headerEnd = text.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            // 非 HTTP 格式：降级为 POST / ，整个帧作为 body
            this.method = HttpMethod.POST;
            this.uri = "/";
            this.path = "/";
            this.params = Collections.emptyMap();
            this.headers = Collections.emptyMap();
            this.body = text.isEmpty() ? new byte[0] : text.getBytes(charset);
            return;
        }

        String headerSection = text.substring(0, headerEnd);
        String rawBody = headerEnd + 4 < text.length() ? text.substring(headerEnd + 4) : "";
        this.body = rawBody.getBytes(charset);

        // 解析请求行
        int firstLf = headerSection.indexOf('\n');
        String requestLine = firstLf >= 0 ? headerSection.substring(0, firstLf).trim() : headerSection.trim();
        String[] parts = requestLine.split("\\s+", 3);
        this.method = parseMethod(parts.length > 0 ? parts[0] : "POST");
        this.uri = parts.length > 1 ? parts[1] : "/";
        this.path = extractPath(this.uri);
        this.params = parseQueryParams(this.uri);

        // 解析请求头
        this.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String[] lines = headerSection.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
    }

    private HttpMethod parseMethod(String raw) {
        try {
            return HttpMethod.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return HttpMethod.POST;
        }
    }

    private String extractPath(String uri) {
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }

    private Map<String, String> parseQueryParams(String uri) {
        int q = uri.indexOf('?');
        if (q < 0 || q + 1 >= uri.length()) {
            return Collections.emptyMap();
        }
        String query = uri.substring(q + 1);
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                map.put(pair, "");
            } else {
                map.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return map;
    }

    @Override
    public String getUri() {
        return uri;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public HttpMethod getMethod() {
        return method;
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public HttpHeader getHeaders() {
        HttpHeader h = HttpHeader.create();
        headers.forEach((k, v) -> h.add(k, v));
        return h;
    }

    @Override
    public Map<String, String> getParams() {
        return params;
    }

    @Override
    public String getParam(String name) {
        return params.get(name);
    }

    @Override
    public String getContentType() {
        return headers.get("Content-Type");
    }

    @Override
    public long getContentLength() {
        String len = headers.get("Content-Length");
        if (len == null) {
            return -1;
        }
        try {
            return Long.parseLong(len.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public byte[] getBody() {
        return body;
    }

    @Override
    public String getBodyString() {
        if (bodyString == null) {
            bodyString = new String(body, charset);
        }
        return bodyString;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(body);
    }

    @Override
    public String getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public int getRemotePort() {
        return remotePort;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }
}
