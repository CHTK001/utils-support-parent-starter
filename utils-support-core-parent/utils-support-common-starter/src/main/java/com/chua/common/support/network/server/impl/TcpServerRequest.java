package com.chua.common.support.network.server.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.nio.NioServerRequest;
import com.chua.common.support.network.server.request.ServerRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.ToString;

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
@ToString
public class TcpServerRequest implements ServerRequest {

    /**
     * 请求方法
     */
    private HttpMethod method;
    /**
     * 完整请求 URI（含查询串）
     */
    private String uri;
    /**
     * 请求路径（不含查询串）
     */
    private String path;
    /**
     * 查询参数
     */
    private Map<String, String> params;
    /**
     * 请求头（大小写不敏感）
     */
    private Map<String, String> headers;
    /**
     * 请求体字节数组
     */
    private byte[] body;
    /**
     * 请求体字符串缓存
     */
    private String bodyString;
    /**
     * 客户端地址
     */
    private final String remoteAddress;
    /**
     * 客户端端口
     */
    private final int remotePort;
    /**
     * 请求属性，用于 Filter 间传递数据
     */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    /**
     * 字符集（从 Content-Type 解析，默认 UTF-8）
     */
    private final Charset charset;

    /**
     * NIO 增量解析状态机：非 null 时表示本次请求已由 {@link #feed} 解析完成，
     * 所有数据访问委托给它。
     */
    private final NioServerRequest nioRequest;
    /**
     * 是否为 NIO 解析路径
     */
    private final boolean nioPath;

    /**
     * 构造 TCP 帧请求。
     *
     * <p>使用旧式逐行解析：不启用 {@link NioServerRequest}。</p>
     *
     * @param body        帧体字节（完整的 HTTP 请求报文）
     * @param remoteAddr  客户端 InetSocketAddress
     * @param charset     请求体字符集
     */
    public TcpServerRequest(byte[] body, InetSocketAddress remoteAddr, Charset charset) {
        this(body, remoteAddr, charset, -1);
    }

    /**
     * 构造 TCP 帧请求。
     *
     * <p>与 {@link #TcpServerRequest(byte[], InetSocketAddress, Charset, long)} 相同，
     * 但字符集使用字符串（来自 {@code setting.getCharset()}），
     * 是 {@code AbstractServer} 调用方到 {@code ServerSetting} 的桥接。</p>
     *
     * @param body          帧体字节（完整的 HTTP 请求报文）
     * @param remoteAddr    客户端 InetSocketAddress
     * @param charset       请求体字符集名称，{@code null}/空时 fallback 为 UTF-8
     * @param maxRequestSize 最大请求体尺寸（字节），<= 0 表示不做限制
     */
    public TcpServerRequest(byte[] body, InetSocketAddress remoteAddr, String charset, long maxRequestSize) {
        this(body, remoteAddr, charset != null && !charset.isEmpty() ? Charset.forName(charset) : StandardCharsets.UTF_8, maxRequestSize);
    }

    /**
     * 构造 TCP 帧请求。
     *
     * <p>优先使用 {@link NioServerRequest} 做增量解析：单帧完整读入时 {@code feed} 返回 1，
     * 所有数据访问直接委托给 NIO 层（解析速度、体感与 {@code NioHttpServer} 一致）；
     * 返回 0/-1（解析失败、超大、半包）或解析异常时退回旧式逐行解析，
     * 降级为 {@code POST /}，允许纯字节流场景仍能写入响应。</p>
     *
     * @param body          帧体字节（完整的 HTTP 请求报文）
     * @param remoteAddr    客户端 InetSocketAddress
     * @param charset       请求体字符集
     * @param maxRequestSize 最大请求体尺寸（字节），来自 {@code setting.getMaxRequestSize()}
     */
    public TcpServerRequest(byte[] body, InetSocketAddress remoteAddr, Charset charset, long maxRequestSize) {
        this.body = body != null ? body : new byte[0];
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
        // 提前确定远端地址（避开 catch 中 return 导致 final 字段未初始化）
        if (remoteAddr != null) {
            this.remoteAddress = remoteAddr.getAddress().getHostAddress();
            this.remotePort = remoteAddr.getPort();
        } else {
            this.remoteAddress = "";
            this.remotePort = 0;
        }
        // <= 0 表示不做限制（历史 3 参构造传入的 -1 即此意）
        long limit = maxRequestSize > 0 ? maxRequestSize : Long.MAX_VALUE;
        // 注意：TCP 场景下本类与 NioServerRequest 一一对应，feed 时一次性传完整帧体
        NioServerRequest nioRequest = new NioServerRequest(remoteAddr, limit, this.charset.name());
        int result = 0;
        try {
            result = nioRequest.feed(ByteBuffer.wrap(this.body));
        } catch (Exception e) {
            // 解析异常（非法请求行等）：退回旧版逐行解析
            result = -1;
            this.nioRequest = null;
            this.nioPath = false;
            parse(this.body, this.charset);
            return;
        }
        if (result == 1) {
            // NIO 解析成功，所有 getter 委托给 NioServerRequest
            this.nioRequest = nioRequest;
            this.nioPath = true;
        } else {
            // 解析失败 / 需要更多数据 / 超长：退回旧版逐行解析，降级为 POST /
            this.nioRequest = null;
            this.nioPath = false;
            parse(this.body, this.charset);
        }
    }

    /**
     * 解析。
     *
     * @param body 帧体字节
     * @param charset 字符集
     */
    private void parse(byte[] body, Charset charset) {
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
        this.headers = Wraps.sortedMap(String.CASE_INSENSITIVE_ORDER);
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

    /**
     * 解析方法。
     *
     * @param raw 方法入参 raw
     * @return Http方法 对象
     */
    private HttpMethod parseMethod(String raw) {
        try {
            return HttpMethod.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return HttpMethod.POST;
        }
    }

    /**
     * extract路径。
     *
     * @param uri URI，不允许为 null
     * @return 结果字符串
     */
    private String extractPath(String uri) {
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }

    /**
     * 解析查询参数。
     *
     * @param uri URI，不允许为 null
     * @return 结果映射，无数据时为空映射
     */
    private Map<String, String> parseQueryParams(String uri) {
        int q = uri.indexOf('?');
        if (q < 0 || q + 1 >= uri.length()) {
            return Collections.emptyMap();
        }
        String query = uri.substring(q + 1);
        Map<String, String> map = Wraps.linkedMap();
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

    /**
     * 是否 NIO 解析路径
     * @return true 表示委托给 NioServerRequest
     */
    private boolean isNio() {
        return nioPath;
    }

    @Override
    public String getUri() {
        return isNio() ? nioRequest.getUri() : uri;
    }

    @Override
    public String getPath() {
        return isNio() ? nioRequest.getPath() : path;
    }

    @Override
    public HttpMethod getMethod() {
        return isNio() ? nioRequest.getMethod() : method;
    }

    @Override
    public String getHeader(String name) {
        return isNio() ? nioRequest.getHeader(name) : headers.get(name);
    }

    @Override
    public HttpHeader getHeaders() {
        return isNio() ? nioRequest.getHeaders() : (headers == null ? HttpHeader.create() : toHttpHeader(headers));
    }

    @Override
    public Map<String, String> getParams() {
        return isNio() ? nioRequest.getParams() : params;
    }

    @Override
    public String getParam(String name) {
        return isNio() ? nioRequest.getParam(name) : (params == null ? null : params.get(name));
    }

    @Override
    public String getContentType() {
        return isNio() ? nioRequest.getContentType() : headers.get("Content-Type");
    }

    @Override
    public long getContentLength() {
        return isNio() ? nioRequest.getContentLength() : (headers == null ? -1 : parsedContentLength(headers.get("Content-Length")));
    }

    @Override
    public byte[] getBody() {
        return isNio() ? nioRequest.getBody() : body;
    }

    @Override
    public String getBodyString() {
        return isNio() ? nioRequest.getBodyString() : (bodyString == null ? validateBody() : bodyString);
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(getBody());
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
        return isNio() ? nioRequest.getAttribute(name) : attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (isNio()) {
            nioRequest.setAttribute(name, value);
        } else {
            attributes.put(name, value);
        }
    }

    /**
     * 构建 HttpHeader，{@link #headers} 转交给 {@link HttpHeader} 使用。
     *
     * @param headers 头信息，不允许为 null
     * @return 结果值
     */
    private static HttpHeader toHttpHeader(Map<String, String> headers) {
        HttpHeader h = HttpHeader.create();
        headers.forEach((k, v) -> h.add(k, v));
        return h;
    }

    /**
     * 缓存 bodyString 并返回。
     *
     * @return 结果字符串
     */
    private String validateBody() {
        bodyString = new String(body, charset);
        return bodyString;
    }

    /**
     * 解析 Content-Length，失败时退回 -1。
     *
     * @param len 头信息
     * @return 结果数值
     */
    private static long parsedContentLength(String len) {
        if (len == null) {
            return -1;
        }
        try {
            return Long.parseLong(len.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
