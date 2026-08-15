package com.chua.common.support.network.invoker.filter;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.request.FormFile;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 调用上下文，承载一次远程调用的请求/响应数据，实现 {@link ServerRequest} 和 {@link ServerResponse}。
 *
 * <p>复用服务端 {@link com.chua.common.support.network.server.filter.ServerFilter} 体系。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class InvocationContext implements ServerRequest, ServerResponse {

    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, String> queryParams = new LinkedHashMap<>();
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private byte[] body;
    private String path;
    private Object result;
    private int statusCode = 200;
    private boolean ended;

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public Object getResult() { return result; }
    public void addHeader(String name, String value) { if (name != null && value != null) headers.put(name, value); }
    public void addHeaders(Map<String, String> h) { if (h != null) headers.putAll(h); }

    @Override public String getUri() { return path; }
    @Override public String getHeader(String name) { return headers.get(name); }
    @Override public HttpHeader getHeaders() { HttpHeader h = HttpHeader.create(); headers.forEach(h::add); return h; }
    @Override public Map<String, String> getParams() { return queryParams; }
    @Override public String getParam(String name) { return queryParams.get(name); }
    @Override public String getContentType() { return headers.get("Content-Type"); }
    @Override public long getContentLength() { return body != null ? body.length : -1; }
    @Override public byte[] getBody() { return body; }
    @Override public String getBodyString() { return body != null ? new String(body, StandardCharsets.UTF_8) : ""; }
    @Override public InputStream getInputStream() { return null; }
    @Override public String getRemoteAddress() { return null; }
    @Override public int getRemotePort() { return 0; }
    @Override public Map<String, Object> getAttributes() { return attributes; }
    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    @Override public Map<String, String> getFormData() { return Collections.emptyMap(); }
    @Override public List<FormFile> getFiles() { return Collections.emptyList(); }
    @Override public HttpMethod getMethod() { return null; }

    @Override public ServerResponse setStatus(int statusCode) { this.statusCode = statusCode; return this; }
    @Override public int getStatus() { return statusCode; }
    @Override public ServerResponse setHeader(String name, String value) { addHeader(name, value); return this; }
    @Override public ServerResponse setContentType(String contentType) { addHeader("Content-Type", contentType); return this; }
    @Override public ServerResponse setBody(byte[] body) { this.body = body; return this; }
    @Override public ServerResponse setBody(String body) { this.body = body != null ? body.getBytes(StandardCharsets.UTF_8) : null; return this; }
    @Override public OutputStream getOutputStream() { return null; }
    @Override public ServerResponse sendRedirect(String location) { return this; }
    @Override public ServerResponse sendError(int statusCode, String message) { this.statusCode = statusCode; return this; }
    @Override public void flush() {}
    @Override public boolean isCommitted() { return ended; }
    @Override public boolean isEnded() { return ended; }
    @Override public ServerResponse setResult(Object result) { this.result = result; return this; }
    @Override public ServerResponse reset() { headers.clear(); queryParams.clear(); body = null; return this; }
    @Override public void end() { this.ended = true; }
    @Override public void writeRaw(byte[] bytes) {}
}