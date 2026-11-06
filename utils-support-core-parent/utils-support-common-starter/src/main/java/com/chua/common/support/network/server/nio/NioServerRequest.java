// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.nio;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.request.FormFile;
import com.chua.common.support.network.server.request.MultipartParser;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.ToString;

/**
 * 非阻塞增量 HTTP 请求解析器(真响应式)。
 *
 * <p>不再阻塞读取 {@link SocketChannel};由事件循环在 OP_READ 就绪时
 * 将数据 {@link #feed(ByteBuffer)} 进来,内部状态机推进:
 * {@code REQUEST_LINE → HEADERS → BODY → COMPLETE}。</p>
 *
 * @author CH
 * <p>每次 feed 返回:1=完整请求解析完成;0=还需更多数据;-1=解析错误。</p>
 * @since 4.0.0.42
 */
@Slf4j
@ToString
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
    /**
     * 最大值请求尺寸
     */
    private final long maxRequestSize;
    /**
     * 默认字符集
     */
    private final Charset defaultCharset;
    /**
     * Method
     */
    private String method;
    /**
     * URI
     */
    private String uri;
    /**
     * 路径
     */
    private String path;
    /**
     * Query字符串
     */
    private String queryString;
    /**
     * HTTP版本
     */
    private String httpVersion = "HTTP/1.1";
    /**
     * headers - 使用 HashMap(忽略大小写通过 toLowerCase 保证)
     */
    private final java.util.Map<String, String> headers = Wraps.map(8);
    /**
     * 请求体
     */
    private byte[] body;
    /**
     * BUF
     */
    private ByteBuffer buf;
    /**
     * BUFHAS数据
     */
    private boolean bufHasData = true;
    /**
     * attributes
     */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 查询参数解析缓存（null 表示尚未解析；随 {@link #resetForNextRequest()} 失效）。
     *
     * <p>Keep-Alive 下同一实例跨请求复用，若直接把上一次的解析结果留下，
     * 第二个请求会读到第一个请求的参数——必须显式清空。</p>
     */
    private Map<String, String> paramsCache;

    /**
     * 头部行解析结果:空行,头部结束。
     */
    private static final int HEADER_END = 1;

    /**
     * 头部行解析结果:已消费一个头部行。
     */
    private static final int HEADER_PARSED = 0;

    /**
     * 头部名转小写用的复用缓冲(单连接单线程 feed,复用安全)。
     */
    private byte[] scratchBuf;

    // ==================== 增量解析状态机 ====================

    /**
     * 当前解析状态
     */
    private ParseState parseState = ParseState.REQUEST_LINE;
    /**
     * 请求体剩余需读取字节数(非 chunked)
     */
    private int bodyRemaining = 0;
    /**
     * 是否 chunked 编码
     */
    private boolean chunked = false;
    /**
     * chunked:当前 chunk 剩余字节
     */
    private int chunkRemaining = 0;
    /**
     * chunked:是否正在读 chunk 头部行
     */
    private boolean chunkHeaderPending = false;

    /**
     * 创建 NioServerRequest 实例(NIO 场景,远端地址从 channel 动态获取)
     * @param channel channel
     * @param maxRequestSize 最大值请求大小，不允许为 null
     * @param charset 字符集，不允许为 null
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
                    // 字节级解析:不再为整行请求行分配临时 String
                    int lf = indexOfLineFeed();
                    if (lf < 0) {
                        return 0;
                    }
                    parseRequestLineFromBuf(lf);
                    parseState = ParseState.HEADERS;
                }
                case HEADERS -> {
                    // 字节级解析:请求头逐行就地取值,只对最终的名字/值建 String
                    int lf = indexOfLineFeed();
                    if (lf < 0) {
                        return 0;
                    }
                    if (parseHeaderLineFromBuf(lf) == HEADER_END) {
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
     * @return 结果数值
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
     * @return 结果数值
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
                // 不预分配 body:原实现按 min(maxRequestSize,1MB) 预分配整块，
                // 而 appendBody 把 body.length 当作「已填充字节数」，导致每个 chunked
                // 请求体都变成「1MB 零字节 + 真实数据」。这里保持 null，由 appendBody
                // 按实际 chunk 长度增量扩容。
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

    /**
     * 追加Body
     * @param src 方法入参 src
     * @param off 方法入参 off
     * @param len 方法入参 len
     * @return 结果值
     */
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
     * <p>仅供 chunked 行协议使用;请求行与请求头走字节级解析,不经过此处。</p>
     * @return 结果字符串
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

    /**
     * 从当前位置查找行尾 {@code \n} 的绝对下标,不移动 position。
     *
     * @return 行尾下标;缓冲中还没有完整行时返回 -1
     */
    private int indexOfLineFeed() {
        int limit = buf.limit();
        for (int i = buf.position(); i < limit; i++) {
            if (buf.get(i) == (byte) '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 计算当前行内容的终点(去掉行尾 {@code \n} 与紧邻的 {@code \r})。
     *
     * @param lf 行尾 {@code \n} 的绝对下标
     * @return 内容终点(不含该下标)
     */
    private int lineContentEnd(int lf) {
        int start = buf.position();
        return lf > start && buf.get(lf - 1) == (byte) '\r' ? lf - 1 : lf;
    }

    /**
     * 判断字节是否会被 {@link String#trim()} 剔除(可视作 HTTP 的 OWS/控制字符)。
     *
     * @param b 字节
     * @return true 表示该字节会被 trim 掉
     */
    private static boolean isTrimByte(byte b) {
        return (b & 0xFF) <= 0x20;
    }

    /**
     * 由解析缓冲的一段区间构造 ISO-8859-1 字符串(构造时复制,不持有缓冲引用)。
     *
     * @param off 起始下标
     * @param len 长度
     * @return 字符串,长度为 0 时返回空串
     */
    private String asciiString(int off, int len) {
        if (len <= 0) {
            return "";
        }
        return new String(buf.array(), buf.arrayOffset() + off, len, StandardCharsets.ISO_8859_1);
    }

    /**
     * 判断缓冲中从 {@code off} 开始的字节是否与给定 ASCII 字面量完全相同。
     *
     * @param off      起始下标
     * @param expected 期望字面量
     * @return true 表示完全相同
     */
    private boolean matchesAscii(int off, String expected) {
        byte[] a = buf.array();
        int base = buf.arrayOffset() + off;
        for (int i = 0; i < expected.length(); i++) {
            if (a[base + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 取得可复用的临时字节数组(头部名转小写用),避免每请求再分配一次。
     *
     * <p>同一连接的 {@link NioServerRequest} 在任一时刻只有一个线程 feed
     * (事件循环内联分发,或 worker 处理期间摘除读兴趣),因此复用安全。</p>
     *
     * @param len 需要的长度
     * @return 长度不小于 {@code len} 的字节数组
     */
    private byte[] scratch(int len) {
        byte[] s = scratchBuf;
        if (s == null || s.length < len) {
            s = new byte[Math.max(len, 64)];
            scratchBuf = s;
        }
        return s;
    }

    /**
     * 读取请求方法:常见方法直接返回常量,省掉每个请求一次字符串分配。
     *
     * @param off 起始下标
     * @param len 长度
     * @return 方法名
     */
    private String methodOrString(int off, int len) {
        return switch (len) {
            case 3 -> matchesAscii(off, "GET") ? "GET" : asciiString(off, len);
            case 4 -> matchesAscii(off, "POST") ? "POST"
                    : matchesAscii(off, "PUT") ? "PUT"
                    : matchesAscii(off, "HEAD") ? "HEAD"
                    : asciiString(off, len);
            case 5 -> matchesAscii(off, "PATCH") ? "PATCH" : asciiString(off, len);
            case 6 -> matchesAscii(off, "DELETE") ? "DELETE" : asciiString(off, len);
            case 7 -> matchesAscii(off, "OPTIONS") ? "OPTIONS" : asciiString(off, len);
            default -> asciiString(off, len);
        };
    }

    /**
     * 直接在解析缓冲上解析请求行,不产生整行临时字符串。
     *
     * <p>与原实现逐字对齐:第一个空格前为方法,第二个空格前为 URI(无第二个空格时
     * 直到行尾),其后的 HTTP 版本按 {@code trim()} 语义去掉两端空白。URI 中的
     * {@code ?} 之后为查询串,无查询串时 {@code path} 直接复用 {@code uri}。</p>
     *
     * @param lf 行尾 {@code \n} 的绝对下标
     */
    private void parseRequestLineFromBuf(int lf) {
        int start = buf.position();
        int end = lineContentEnd(lf);
        int s1 = -1;
        for (int i = start; i < end; i++) {
            if (buf.get(i) == (byte) ' ') {
                s1 = i;
                break;
            }
        }
        if (s1 < 0) {
            buf.position(lf + 1);
            throw new IllegalArgumentException("Invalid request line: " + asciiString(start, end - start));
        }
        int s2 = -1;
        for (int i = s1 + 1; i < end; i++) {
            if (buf.get(i) == (byte) ' ') {
                s2 = i;
                break;
            }
        }
        this.method = methodOrString(start, s1 - start);
        int uriStart = s1 + 1;
        int uriEnd = s2 > 0 ? s2 : end;
        this.uri = asciiString(uriStart, uriEnd - uriStart);
        if (s2 > 0) {
            int vs = s2 + 1;
            int ve = end;
            while (vs < ve && isTrimByte(buf.get(vs))) {
                vs++;
            }
            while (ve > vs && isTrimByte(buf.get(ve - 1))) {
                ve--;
            }
            this.httpVersion = ve - vs == 8 && matchesAscii(vs, "HTTP/1.1")
                    ? "HTTP/1.1" : asciiString(vs, ve - vs);
        }
        buf.position(lf + 1);
        int q = this.uri.indexOf('?');
        if (q >= 0) {
            this.path = this.uri.substring(0, q);
            this.queryString = this.uri.substring(q + 1);
        } else {
            this.path = this.uri;
            this.queryString = null;
        }
    }

    /**
     * 直接在解析缓冲上解析一个请求头行,不产生整行临时字符串。
     *
     * <p>语义与原实现一致:冒号前的名字按 {@code trim()} 去空白并转小写,冒号后的值
     * 按 {@code trim()} 去空白;没有冒号(或名字为空)的行直接忽略。</p>
     *
     * @param lf 行尾 {@code \n} 的绝对下标
     * @return {@link #HEADER_END} 表示空行(头部结束),{@link #HEADER_PARSED} 表示已消费一行
     */
    private int parseHeaderLineFromBuf(int lf) {
        int start = buf.position();
        int end = lineContentEnd(lf);
        if (end == start) {
            buf.position(lf + 1);
            return HEADER_END;
        }
        int colon = -1;
        for (int i = start; i < end; i++) {
            if (buf.get(i) == (byte) ':') {
                colon = i;
                break;
            }
        }
        buf.position(lf + 1);
        if (colon <= start) {
            return HEADER_PARSED;
        }
        int nameEnd = colon;
        while (nameEnd > start && isTrimByte(buf.get(nameEnd - 1))) {
            nameEnd--;
        }
        int valueStart = colon + 1;
        while (valueStart < end && isTrimByte(buf.get(valueStart))) {
            valueStart++;
        }
        int valueEnd = end;
        while (valueEnd > valueStart && isTrimByte(buf.get(valueEnd - 1))) {
            valueEnd--;
        }
        headers.put(lowerAsciiString(start, nameEnd - start), asciiString(valueStart, valueEnd - valueStart));
        return HEADER_PARSED;
    }

    /**
     * 读取头部名并就地转小写(HTTP 头部名只允许 ASCII,超出范围按原字节保留)。
     *
     * @param off 起始下标
     * @param len 长度
     * @return 小写头部名
     */
    private String lowerAsciiString(int off, int len) {
        if (len <= 0) {
            return "";
        }
        byte[] a = buf.array();
        int base = buf.arrayOffset() + off;
        boolean hasUpper = false;
        for (int i = 0; i < len; i++) {
            byte b = a[base + i];
            if (b >= 'A' && b <= 'Z') {
                hasUpper = true;
                break;
            }
        }
        if (!hasUpper) {
            return new String(a, base, len, StandardCharsets.ISO_8859_1);
        }
        byte[] lower = scratch(len);
        for (int i = 0; i < len; i++) {
            byte b = a[base + i];
            lower[i] = b >= 'A' && b <= 'Z' ? (byte) (b + 32) : b;
        }
        return new String(lower, 0, len, StandardCharsets.ISO_8859_1);
    }

    /**
     * 当前解析状态(供事件循环判断)
     * @return 解析状态 对象
     */
    ParseState parseState() {
        return parseState;
    }

    @Override public String getUri() { return uri; }
    @Override public String getPath() { return path; }
    @Override public HttpMethod getMethod() {
        if (method == null || method.isEmpty()) {
            return HttpMethod.GET;
        }
        try {
            return HttpMethod.valueOf(method.toUpperCase());
        } catch (IllegalArgumentException e) {
            return HttpMethod.OPTIONS;
        }
    }
    @Override public String getHeader(String name) { return headers.get(name.toLowerCase()); }
    @Override
    public HttpHeader getHeaders() {
        HttpHeader h = HttpHeader.create();
        headers.forEach(h::add);
        return h;
    }
    @Override public Map<String, String> getParams() {
        // 查询参数在每个请求中被 UrlMapping / 参数解析 / 业务代码反复读取，
        // 原实现每次调用都重新 split + URLDecode（一次请求可达 5~10 次）。
        // Keep-Alive 下同一 request 实例会被 resetForNextRequest 复用，故缓存随重置失效。
        Map<String, String> cached = this.paramsCache;
        if (cached != null) {
            return cached;
        }
        if (queryString == null || queryString.isEmpty()) {
            this.paramsCache = Map.of();
            return this.paramsCache;
        }
        Map<String, String> map = Wraps.linkedMap();
        for (String pair : queryString.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length > 0) {
                map.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
            }
        }
        Map<String, String> resolved = Map.copyOf(map);
        this.paramsCache = resolved;
        return resolved;
    }
    @Override public String getParam(String name) { return getParams().get(name); }
    @Override public String getContentType() { return headers.get("content-type"); }
    @Override public long getContentLength() {
        String cl = headers.get("content-length");
        if (cl == null) {
            return -1;
        }
        try {
            return Long.parseLong(cl);
        } catch (NumberFormatException e) {
            return -1;
        }
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
        } catch (IOException e) {
            log.debug("[nio] 获取远端地址失败，返回 unknown: {}", e.getMessage());
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
        } catch (IOException e) {
            log.debug("[nio] 获取远端端口失败，返回 0: {}", e.getMessage());
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
            Map<String, String> form = Wraps.linkedMap();
            for (String pair : bodyStr.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length > 0) {
                    form.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
                }
            }
            return form;
        }
        if (lower.startsWith("multipart/form-data")) {
            MultipartParser parser = ServiceProvider.of(MultipartParser.class).getExtension("fileupload");
            if (parser != null) {
                return parser.parseFormFields(getBody(), ct);
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

    /**
     * 解码
     * @param value 字符串
     * @return 返回值
    */
    private String decode(String value) {
        return URLDecoder.decode(value, defaultCharset);
    }
    /**
     * 解析Charset
     * @return 字符集 对象
     */
    private Charset resolveCharset() {
        String contentType = getContentType();
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String v = part.trim();
                if (v.regionMatches(true, 0, "charset=", 0, 8)) {
                    try {
                        return Charset.forName(v.substring(8).trim());
                    } catch (Exception ignored) {
                        return defaultCharset;
                    }
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
    public String getHttpVersion() {
        return httpVersion;
    }

    /**
     * 重置解析状态以复用同一实例处理 Keep-Alive 连接的下一条请求。
     * <p>保留行缓冲中未消费的数据(可能是 pipeline 的下一条请求前缀),
     * 仅清空已解析字段并将状态机归位。</p>
     */
    public void resetForNextRequest() {
        method = null;
        uri = null;
        path = null;
        queryString = null;
        httpVersion = "HTTP/1.1";
        headers.clear();
        body = null;
        attributes.clear();
        paramsCache = null;
        // 重置状态机(保留缓冲中未消费的数据,供 Keep-Alive 下一条请求复用)
        parseState = ParseState.REQUEST_LINE;
        bodyRemaining = 0;
        chunked = false;
        chunkRemaining = 0;
        chunkHeaderPending = false;
    }
}

