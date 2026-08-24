package com.chua.common.support.network.server.nio;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.request.FormFile;
import com.chua.common.support.network.server.request.MultipartParser;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.spi.ServiceProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 非阻塞增量 HTTP 请求解析器(真响应式)。
 *
 * <p>不再阻塞读取 {@link SocketChannel};由事件循环在 OP_READ 就绪时
 * 将数据 {@link #feed(ByteBuffer)} 进来,内部状态机推进:
 * {@code REQUEST_LINE → HEADERS → BODY → COMPLETE}。</p>
 *
 * @author CH
 * <p>每次 feed 返回:1=完整请求解析完成;0=还需更多数据;-1=解析错误。</p>
 */
public class NioServerRequest implements ServerRequest {

    /**
     * HTTP 请求增量解析状态机的状态枚举。
     *
     * <p>状态推进路径:
     * {@code REQUEST_LINE → HEADERS → BODY → COMPLETE}。
     * 每次调用 {@link #feed(ByteBuffer)} 时从当前状态继续消费数据,
     * 数据不足则停留在原状态等待下次 feed。</p>
     *
     * @author CH
     * @since 2026/08/12
     */
    public enum ParseState {
        /**
         * 请求行解析中:等待 "METHOD URI VERSION\r\n" 完整一行,
         * 解析出 method/uri/path/queryString/httpVersion 后进入 HEADERS
         */
        REQUEST_LINE,
        /**
         * 头部解析中:逐行读取请求头直到空行(\r\n),
         * 根据 Transfer-Encoding/Content-Length 决定进入 BODY 或直接 COMPLETE
         */
        HEADERS,
        /**
         * 请求体接收中:非 chunked 按 Content-Length 剩余字节数消费;
         * chunked 则逐 chunk 推进(读 chunk 头 → 读 chunk 体 → 消费尾部 CRLF)
         */
        BODY,
        /**
         * 解析完成:完整请求已就绪,可交由 handler 链处理;
         * Keep-Alive 场景经 {@link #resetForNextRequest()} 重置回 REQUEST_LINE
         */
        COMPLETE
    }

    /**
     * 通道(阻塞/非阻塞 NIO 场景使用;AIO 等无通道场景为 null,
     * 此时远端地址取 {@link #remoteAddress} 预存值)
     */
    private final SocketChannel channel;
    /**
     * 预存的远端地址:构造时一次性取出,避免热路径反复系统调用;
     * 无通道场景(AIO)必填,有通道场景可为 null
     */
    private final SocketAddress remoteAddress;
    /** 最大值请求尺寸 */
    private final long maxRequestSize;
    /** 默认字符集 */
    private final Charset defaultCharset;
    /** Method */
    private String method;
    /** URI */
    private String uri;
    /** 路径 */
    private String path;
    /** Query字符串 */
    private String queryString;
    /** HTTP版本 */
    private String httpVersion = "HTTP/1.1";
    /** headers - 使用 HashMap(忽略大小写通过 toLowerCase 保证) */
    private final java.util.HashMap<String, String> headers = new java.util.HashMap<>(8);
    /** 请求体 */
    private byte[] body;
    /** 行解析缓冲(REQUEST_LINE/HEADERS/chunked 头);懒分配,空闲连接(未收到数据)不占用
     *  <p>百万级空闲连接场景,每连接省 8KB,整体省数十 GB,是支撑高连接数的关键</p> */
    /** BUF */
    private ByteBuffer buf;
    /** BUFHAS数据 */
    private boolean bufHasData = true;
    /** attributes */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    // ==================== 增量解析状态机 ====================

    /** 当前解析状态 */
    private ParseState parseState = ParseState.REQUEST_LINE;
    /** 请求体剩余需读取字节数(非 chunked) */
    private int bodyRemaining = 0;
    /** 是否 chunked 编码 */
    private boolean chunked = false;
    /** chunked:当前 chunk 剩余字节 */
    private int chunkRemaining = 0;
    /** chunked:是否正在读 chunk 头部行 */
    private boolean chunkHeaderPending = false;

    /**
     * 创建 NioServerRequest 实例(NIO 场景,远端地址从 channel 动态获取)
     * @param channel channel
     * @param long long
     * @param String String
     */
    public NioServerRequest(SocketChannel channel, long maxRequestSize, String charset) {
        this.channel = channel;
        this.remoteAddress = null;
        this.maxRequestSize = maxRequestSize;
        this.defaultCharset = Charset.forName(charset);
    }

    /**
     * 创建 NioServerRequest 实例(传输无关场景:AIO/IOCP 等无 {@link SocketChannel} 的实现,
     * 解析状态机与 NIO 完全共用,仅远端地址由调用方在连接建立时预存传入)
     *
     * @param remoteAddress  远端地址(连接建立时预存,可为 null 表示未知)
     * @param maxRequestSize 最大请求体尺寸(字节)
     * @param charset        默认字符集名称
     */
    public NioServerRequest(SocketAddress remoteAddress, long maxRequestSize, String charset) {
        this.channel = null;
        this.remoteAddress = remoteAddress;
        this.maxRequestSize = maxRequestSize;
        this.defaultCharset = Charset.forName(charset);
    }

    /**
     * 非阻塞增量解析:将新读到的数据并入解析缓冲并推进状态机。
     *
     * @param data 事件循环读到的数据(可空,表示无新数据仅推进)
     * @return 1=完整请求已解析完成;0=需要更多数据;-1=解析错误
     */
    public int feed(ByteBuffer data) {
        ensureBuf();
        // BODY 阶段:直接消费 data,不并入行解析缓冲,避免大 body 撑爆 8K 缓冲
        if (parseState == ParseState.BODY && !chunked) {
            if (data != null && data.hasRemaining()) {
                int toCopy = Math.min(data.remaining(), bodyRemaining);
                if (body == null) {
                    body = new byte[bodyRemaining];
                }
                data.get(body, body.length - bodyRemaining, toCopy);
                bodyRemaining -= toCopy;
            }
            if (bodyRemaining > 0) {
                return 0;
            }
            parseState = ParseState.COMPLETE;
            return 1;
        }

        // 行解析阶段(REQUEST_LINE / HEADERS / chunked BODY):并入缓冲
        if (data != null && data.hasRemaining()) {
            buf.compact();
            // 按需拷贝:只放入 buf 能容纳的部分,剩余留在 data(调用方 compact 保留,下一轮继续 feed)。
            // 原实现要求 data 全部装入,SSL 路径一次 read 解出 16KB 明文(含 keep-alive 后续数据)
            // 超过 8KB 行缓冲即误报"超长行"(-1),导致请求解析失败、连接被关闭
            int toCopy = Math.min(data.remaining(), buf.remaining());
            if (toCopy == 0) {
                // buf 已满但仍未解析出完整行 → 真·超长行
                return -1;
            }
            // 零分配:直接从 data 转移到 buf,避免临时 byte[] 分配
            int oldLimit = data.limit();
            data.limit(data.position() + toCopy);
            buf.put(data);
            data.limit(oldLimit);
            buf.flip();
        }

        while (true) {
            switch (parseState) {
                case REQUEST_LINE -> {
                    String line = nextLineFromBuf();
                    if (line == null) {
                        return 0;
                    }
                    parseRequestLine(line);
                    parseState = ParseState.HEADERS;
                }
                case HEADERS -> {
                    String line = nextLineFromBuf();
                    if (line == null) {
                        return 0;
                    }
                    if (line.isEmpty()) {
                        // 头结束,确定请求体读取策略
                        String te = headers.get("transfer-encoding");
                        if (te != null && te.toLowerCase().contains("chunked")) {
                            chunked = true;
                            chunkHeaderPending = true;
                            parseState = ParseState.BODY;
                        } else {
                            String cl = headers.get("content-length");
                            bodyRemaining = cl != null ? Integer.parseInt(cl.trim()) : 0;
                            if (bodyRemaining > maxRequestSize) {
                                return -1;
                            }
                            if (bodyRemaining == 0) {
                                body = new byte[0];
                                parseState = ParseState.COMPLETE;
                                return 1;
                            }
                            parseState = ParseState.BODY;
                            // BODY 阶段直接消费 buf 中已有数据
                            return feedBufBody();
                        }
                    } else {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                        }
                    }
                }
                case BODY -> {
                    if (chunked) {
                        int r = feedChunked();
                        if (r == 0) {
                            return 0;
                        }
                        if (r < 0) {
                            return -1;
                        }
                    } else {
                        int r = feedBufBody();
                        if (r == 0) {
                            return 0;
                        }
                        if (r < 0) {
                            return -1;
                        }
                    }
                }
                case COMPLETE -> {
                    return 1;
                }
            }
        }
    }

    /**
     * 从解析缓冲消费请求体(非 chunked)。返回 0=还需更多;1=完成;-1=错误。
     */
    private int feedBufBody() {
        if (bodyRemaining == 0) {
            parseState = ParseState.COMPLETE;
            return 1;
        }
        int avail = buf.remaining();
        if (avail == 0) {
            return 0;
        }
        int toCopy = Math.min(avail, bodyRemaining);
        if (body == null) {
            body = new byte[bodyRemaining];
        }
        buf.get(body, body.length - bodyRemaining, toCopy);
        bodyRemaining -= toCopy;
        if (bodyRemaining > 0) {
            return 0;
        }
        parseState = ParseState.COMPLETE;
        return 1;
    }

    /**
     * 消费 chunked 编码:推进 chunk 头/体,直至终止 chunk(0)。返回 0=还需更多;1=完成;-1=错误。
     */
    private int feedChunked() {
        while (true) {
            if (chunkHeaderPending) {
                String line = nextLineFromBuf();
                if (line == null) {
                    return 0;
                }
                int semi = line.indexOf(';');
                String sizePart = (semi > 0 ? line.substring(0, semi) : line).trim();
                try {
                    chunkRemaining = Integer.parseInt(sizePart, 16);
                } catch (NumberFormatException e) {
                    return -1;
                }
                if (chunkRemaining == 0) {
                    // 终止 chunk:消费尾部 CRLF
                    if (nextLineFromBuf() == null) {
                        return 0;
                    }
                    parseState = ParseState.COMPLETE;
                    return 1;
                }
                if (chunkRemaining > maxRequestSize) {
                    return -1;
                }
                chunkHeaderPending = false;
                if (body == null) {
                    body = new byte[(int) Math.min(maxRequestSize, 1024 * 1024)];
                }
            } else {
                int avail = buf.remaining();
                if (avail == 0) {
                    return 0;
                }
                int toCopy = Math.min(avail, chunkRemaining);
                body = appendBody(buf.array(), buf.arrayOffset() + buf.position(), toCopy);
                buf.position(buf.position() + toCopy);
                chunkRemaining -= toCopy;
                if (chunkRemaining > 0) {
                    return 0;
                }
                // chunk 结束,消费尾部 CRLF,进入下一个 chunk 头
                chunkHeaderPending = true;
                if (nextLineFromBuf() == null) {
                    return 0;
                }
            }
        }
    }

    /** 追加Body */
    private byte[] appendBody(byte[] src, int off, int len) {
        int newLen = (body != null ? body.length : 0) + len;
        byte[] out = new byte[newLen];
        if (body != null) {
            System.arraycopy(body, 0, out, 0, body.length);
        }
        System.arraycopy(src, off, out, newLen - len, len);
        return out;
    }

    /**
     * 懒分配行解析缓冲:空闲连接(从未收到数据)不占用 8KB。
     * 初始为"空读取模式":limit=0,首次 feed 时 compact 不会误移动垃圾数据。
     */
    private void ensureBuf() {
        if (buf == null) {
            buf = ByteBuffer.allocate(8192);
            buf.limit(0);
        }
    }

    /**
     * 从解析缓冲读取一行(以 \n 结尾,剔除 \r)。找不到完整行返回 null(不移除数据)。
     */
    private String nextLineFromBuf() {
        int start = buf.position();
        int limit = buf.limit();
        for (int i = start; i < limit; i++) {
            if (buf.get(i) == (byte) '\n') {
                int lineEnd = i;
                int contentLen = lineEnd - start;
                if (contentLen > 0 && buf.get(lineEnd - 1) == (byte) '\r') {
                    contentLen--;
                }
                String line = new String(buf.array(), buf.arrayOffset() + start, contentLen,
                        StandardCharsets.ISO_8859_1);
                buf.position(lineEnd + 1);
                return line;
            }
        }
        return null;
    }

    /** 解析RequestLine */
    private void parseRequestLine(String line) {
        int s1 = line.indexOf(' ');
        if (s1 < 0) {
            throw new IllegalArgumentException("Invalid request line: " + line);
        }
        int s2 = line.indexOf(' ', s1 + 1);
        this.method = line.substring(0, s1);
        this.uri = s2 > 0 ? line.substring(s1 + 1, s2) : line.substring(s1 + 1);
        this.httpVersion = s2 > 0 ? line.substring(s2 + 1).trim() : "HTTP/1.1";
        int q = uri.indexOf('?');
        if (q >= 0) {
            this.path = uri.substring(0, q);
            this.queryString = uri.substring(q + 1);
        } else {
            this.path = uri;
            this.queryString = null;
        }
    }

    /** 当前解析状态(供事件循环判断) */
    ParseState parseState() {
        return parseState;
    }

    @Override public String getUri() { return uri; }
    @Override public String getPath() { return path; }
    @Override public HttpMethod getMethod() {
        if (method == null || method.isEmpty()) {
            return HttpMethod.GET;
        }
        try { return HttpMethod.valueOf(method.toUpperCase()); } catch (IllegalArgumentException e) { return HttpMethod.OPTIONS; }
    }
    @Override public String getHeader(String name) { return headers.get(name.toLowerCase()); }
    @Override public HttpHeader getHeaders() { HttpHeader h = HttpHeader.create(); headers.forEach(h::add); return h; }
    @Override public Map<String, String> getParams() {
        if (queryString == null || queryString.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : queryString.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length > 0) map.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
        }
        return map;
    }
    @Override public String getParam(String name) { return getParams().get(name); }
    @Override public String getContentType() { return headers.get("content-type"); }
    @Override public long getContentLength() {
        String cl = headers.get("content-length");
        if (cl == null) {
            return -1;
        }
        try { return Long.parseLong(cl); } catch (NumberFormatException e) { return -1; }
    }
    @Override public byte[] getBody() { return body != null ? body : new byte[0]; }
    @Override public String getBodyString() { return new String(getBody(), resolveCharset()); }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(getBody()); }
    @Override public String getRemoteAddress() {
        // 优先使用预存地址(传输无关场景);否则从 channel 动态获取(NIO 场景)
        if (remoteAddress instanceof InetSocketAddress inet) {
            return inet.getHostString();
        }
        try {
            SocketAddress sa = channel != null ? channel.getRemoteAddress() : null;
            if (sa instanceof InetSocketAddress addr) {
                return addr.getHostString();
            }
        } catch (IOException ignored) {
        }
        return "unknown";
    }
    @Override public int getRemotePort() {
        if (remoteAddress instanceof InetSocketAddress inet) {
            return inet.getPort();
        }
        try {
            SocketAddress sa = channel != null ? channel.getRemoteAddress() : null;
            if (sa instanceof InetSocketAddress addr) {
                return addr.getPort();
            }
        } catch (IOException ignored) {
        }
        return 0;
    }
    @Override public Map<String, Object> getAttributes() { return attributes; }
    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    @Override public Map<String, String> getFormData() {
        String ct = getContentType();
        if (ct == null) {
            return Collections.emptyMap();
        }
        String lower = ct.toLowerCase();
        if (lower.startsWith("application/x-www-form-urlencoded")) {
            String bodyStr = getBodyString();
            if (bodyStr.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, String> form = new LinkedHashMap<>();
            for (String pair : bodyStr.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length > 0) form.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) {
                    : "");
                }
            }
            return form;
        }
        if (lower.startsWith("multipart/form-data")) {
            MultipartParser parser = ServiceProvider.of(MultipartParser.class).getExtension("fileupload");
            if (parser != null) return parser.parseFormFields(getBody() {
                , ct);
            }
        }
        return Collections.emptyMap();
    }
    @Override public List<FormFile> getFiles() {
        String ct = getContentType();
        if (ct == null || !ct.toLowerCase().startsWith("multipart/form-data")) {
            return Collections.emptyList();
        }
        MultipartParser parser = ServiceProvider.of(MultipartParser.class).getExtension("fileupload");
        if (parser == null) {
            return Collections.emptyList();
        }
        return parser.parse(getBody(), ct);
    }

    /** 解码 */
    private String decode(String value) { return URLDecoder.decode(value, defaultCharset); }
    /** 解析Charset */
    private Charset resolveCharset() {
        String contentType = getContentType();
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String v = part.trim();
                if (v.regionMatches(true, 0, "charset=", 0, 8)) {
                    try { return Charset.forName(v.substring(8).trim()); } catch (Exception ignored) { return defaultCharset; }
                }
            }
        }
        return defaultCharset;
    }
    /**
     * 获取 HTTP 协议版本(如 "HTTP/1.1"),供 Keep-Alive 判断使用。
     *
     * @return 协议版本字符串
     */
    public String getHttpVersion() { return httpVersion; }

    /**
     * 重置解析状态以复用同一实例处理 Keep-Alive 连接的下一条请求。
     * <p>保留行缓冲中未消费的数据(可能是 pipeline 的下一条请求前缀),
     * 仅清空已解析字段并将状态机归位。</p>
     */
    public void resetForNextRequest() {
        method = null; uri = null; path = null; queryString = null;
        httpVersion = "HTTP/1.1"; headers.clear(); body = null;
        attributes.clear();
        // 重置状态机(保留缓冲中未消费的数据,供 Keep-Alive 下一条请求复用)
        parseState = ParseState.REQUEST_LINE;
        bodyRemaining = 0;
        chunked = false;
        chunkRemaining = 0;
        chunkHeaderPending = false;
    }
}

