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

public class NioServerRequest implements ServerRequest {

    private final SocketChannel channel;
    private final long maxRequestSize;
    private final Charset defaultCharset;
    private String method;
    private String uri;
    private String path;
    private String queryString;
    private String httpVersion = "HTTP/1.1";
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private byte[] body;
    private ByteBuffer buf = ByteBuffer.allocate(8192);
    private boolean bufHasData = true;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public NioServerRequest(SocketChannel channel, long maxRequestSize, String charset) {
        this.channel = channel;
        this.maxRequestSize = maxRequestSize;
        this.defaultCharset = Charset.forName(charset);
    }

    boolean parse() {
        try {
            buf.flip();
            bufHasData = true;
            if (!readLine()) return false;
            parseRequestLine(lineBuf.toString());
            while (readLine()) {
                String line = lineBuf.toString();
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
            }
            readBody();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void parseRequestLine(String line) {
        int s1 = line.indexOf(' ');
        if (s1 < 0) throw new IllegalArgumentException("Invalid request line: " + line);
        int s2 = line.indexOf(' ', s1 + 1);
        this.method = line.substring(0, s1);
        this.uri = s2 > 0 ? line.substring(s1 + 1, s2) : line.substring(s1 + 1);
        this.httpVersion = s2 > 0 ? line.substring(s2 + 1).trim() : "HTTP/1.1";
        int q = uri.indexOf('?');
        if (q >= 0) { this.path = uri.substring(0, q); this.queryString = uri.substring(q + 1); }
        else { this.path = uri; this.queryString = null; }
    }

    private void readBody() throws IOException {
        String cl = headers.get("content-length");
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase().contains("chunked")) body = readChunkedBody();
        else if (cl != null) {
            int len = Integer.parseInt(cl.trim());
            if (len > maxRequestSize) throw new IllegalArgumentException("请求体超过最大限制: " + maxRequestSize);
            body = readExactBytes(len);
        } else body = new byte[0];
    }

    private byte[] readChunkedBody() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            if (!readLine()) break;
            int chunkSize;
            try { chunkSize = Integer.parseInt(lineBuf.toString().trim(), 16); }
            catch (NumberFormatException e) { break; }
            if (chunkSize == 0) { readLine(); break; }
            byte[] chunk = readExactBytes(chunkSize);
            out.write(chunk);
            readLine();
        }
        return out.toByteArray();
    }

    private byte[] readExactBytes(int n) throws IOException {
        if (n == 0) return new byte[0];
        byte[] data = new byte[n];
        int off = 0;
        int retries = 0;
        while (off < n) {
            if (buf.hasRemaining()) {
                int toCopy = Math.min(buf.remaining(), n - off);
                buf.get(data, off, toCopy);
                off += toCopy;
            } else {
                buf.clear();
                int r = channel.read(buf);
                if (r == 0 && retries++ < 3) {
                    continue;
                }
                if (r < 0) {
                    throw new IOException("Unexpected EOF, expected " + (n - off) + " more bytes");
                }
                retries = 0;
                buf.flip();
            }
        }
        return data;
    }

    private final StringBuilder lineBuf = new StringBuilder(256);

    private boolean readLine() throws IOException {
        lineBuf.setLength(0);
        while (true) {
            // 批量扫描缓冲区中的 \n，避免逐字节方法调用
            int start = buf.position();
            int limit = buf.limit();
            boolean found = false;
            for (int i = start; i < limit; i++) {
                if (buf.get(i) == (byte) '\n') {
                    int lineEnd = i;
                    int contentLen = lineEnd - start;
                    if (contentLen > 0 && buf.get(lineEnd - 1) == (byte) '\r') {
                        contentLen--;
                    }
                    appendLineBytes(start, contentLen);
                    buf.position(lineEnd + 1); // 跳过 \n
                    found = true;
                    break;
                }
            }
            if (found) {
                return true;
            }
            // 缓冲内无换行：暂存剩余内容后读更多
            int rem = buf.remaining();
            if (rem > 0) {
                appendLineBytes(buf.position(), rem);
                buf.position(buf.limit());
            }
            buf.clear();
            int n = channel.read(buf);
            if (n <= 0) {
                bufHasData = false;
                return lineBuf.length() > 0;
            }
            buf.flip();
            bufHasData = true;
        }
    }

    /**
     * 将缓冲区 [off, off+len) 追加到行缓冲（ISO-8859-1 字节 → char 1:1 映射）。
     */
    private void appendLineBytes(int off, int len) {
        if (len <= 0) {
            return;
        }
        byte[] arr = buf.array();
        for (int i = 0; i < len; i++) {
            lineBuf.append((char) (arr[off + i] & 0xFF));
        }
    }

    private int readByte() throws IOException {
        if (buf.hasRemaining()) return buf.get() & 0xFF;
        buf.clear();
        int n = channel.read(buf);
        if (n == 0) n = channel.read(buf);
        if (n <= 0) { bufHasData = false; return -1; }
        buf.flip();
        bufHasData = true;
        return buf.get() & 0xFF;
    }

    @Override public String getUri() { return uri; }
    @Override public String getPath() { return path; }
    @Override public HttpMethod getMethod() {
        try { return HttpMethod.valueOf(method.toUpperCase()); } catch (IllegalArgumentException e) { return HttpMethod.OPTIONS; }
    }
    @Override public String getHeader(String name) { return headers.get(name.toLowerCase()); }
    @Override public HttpHeader getHeaders() { HttpHeader h = HttpHeader.create(); headers.forEach(h::add); return h; }
    @Override public Map<String, String> getParams() {
        if (queryString == null || queryString.isEmpty()) return Map.of();
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
        if (cl == null) return -1;
        try { return Long.parseLong(cl); } catch (NumberFormatException e) { return -1; }
    }
    @Override public byte[] getBody() { return body != null ? body : new byte[0]; }
    @Override public String getBodyString() { return new String(getBody(), resolveCharset()); }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(getBody()); }
    @Override public String getRemoteAddress() {
        try { SocketAddress sa = channel.getRemoteAddress(); if (sa instanceof InetSocketAddress inet) return inet.getHostString(); }
        catch (IOException ignored) {} return "unknown";
    }
    @Override public int getRemotePort() {
        try { SocketAddress sa = channel.getRemoteAddress(); if (sa instanceof InetSocketAddress inet) return inet.getPort(); }
        catch (IOException ignored) {} return 0;
    }
    @Override public Map<String, Object> getAttributes() { return attributes; }
    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    @Override public Map<String, String> getFormData() {
        String ct = getContentType();
        if (ct == null) return Collections.emptyMap();
        String lower = ct.toLowerCase();
        if (lower.startsWith("application/x-www-form-urlencoded")) {
            String bodyStr = getBodyString();
            if (bodyStr.isEmpty()) return Collections.emptyMap();
            Map<String, String> form = new LinkedHashMap<>();
            for (String pair : bodyStr.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length > 0) form.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
            }
            return form;
        }
        if (lower.startsWith("multipart/form-data")) {
            MultipartParser parser = ServiceProvider.of(MultipartParser.class).getExtension("fileupload");
            if (parser != null) return parser.parseFormFields(getBody(), ct);
        }
        return Collections.emptyMap();
    }
    @Override public List<FormFile> getFiles() {
        String ct = getContentType();
        if (ct == null || !ct.toLowerCase().startsWith("multipart/form-data")) return Collections.emptyList();
        MultipartParser parser = ServiceProvider.of(MultipartParser.class).getExtension("fileupload");
        if (parser == null) return Collections.emptyList();
        return parser.parse(getBody(), ct);
    }

    private String decode(String value) { return URLDecoder.decode(value, defaultCharset); }
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
    String getHttpVersion() { return httpVersion; }
    void resetForNextRequest() {
        method = null; uri = null; path = null; queryString = null;
        httpVersion = "HTTP/1.1"; headers.clear(); body = null;
        lineBuf.setLength(0); attributes.clear();
    }
}
