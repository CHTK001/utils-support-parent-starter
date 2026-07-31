package com.chua.protocol.support.network.protocol.request;

import java.time.LocalDateTime;

/**
 * HTTP servlet request stub.
 * @author CH
 */
public class HttpServletRequest implements ServletRequest {
    private String requestId;
    /**
     * 方法名
     */
    private String method;
    /**
     * 地址
     */
    private String url;
    /**
     * 路径
     */
    private String path;
    private String queryString;
    private String protocolVersion;
    private String serverIp;
    private int serverPort;
    private String clientIp;
    private int clientPort;
    private String contentType;
    private Long contentLength;
    private RequestHeaders headers;
    private RequestParameters parameters;
    private RequestAttributes attributes;
    /**
     * 请求体
     */
    private byte[] body;
    private LocalDateTime requestTime;

    public static Builder builder() {
        return new Builder();
    }

    public static HttpServletRequest from(ServletRequest request) {
        if (request instanceof HttpServletRequest httpServletRequest) {
            return httpServletRequest;
        }
        Builder b = builder();
        if (request != null) {
            b.requestId(request.getRequestId())
                    .method(request.getMethod())
                    .path(request.getPath())
                    .queryString(request.getQueryString())
                    .headers(request.getHeaders())
                    .body(request.getBody())
                    .attributes(new RequestAttributes());
        }
        return b.build();
    }

    @Override
    public Object getAttribute(String name) {
        return attributes == null ? null : attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (attributes == null) {
            attributes = new RequestAttributes();
        }
        attributes.set(name, value);
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String getQueryString() {
        return queryString;
    }

    @Override
    public String getRequestId() {
        return requestId;
    }

    @Override
    public byte[] getBody() {
        return body;
    }

    @Override
    public RequestHeaders getHeaders() {
        return headers;
    }

    public static final class Builder {
        private final HttpServletRequest r = new HttpServletRequest();

        public Builder requestId(String v) { r.requestId = v; return this; }
        public Builder method(String v) { r.method = v; return this; }
        public Builder url(String v) { r.url = v; return this; }
        public Builder path(String v) { r.path = v; return this; }
        public Builder queryString(String v) { r.queryString = v; return this; }
        public Builder protocolVersion(String v) { r.protocolVersion = v; return this; }
        public Builder serverIp(String v) { r.serverIp = v; return this; }
        public Builder serverPort(int v) { r.serverPort = v; return this; }
        public Builder clientIp(String v) { r.clientIp = v; return this; }
        public Builder clientPort(int v) { r.clientPort = v; return this; }
        public Builder contentType(String v) { r.contentType = v; return this; }
        public Builder contentLength(Long v) { r.contentLength = v; return this; }
        public Builder headers(RequestHeaders v) { r.headers = v; return this; }
        public Builder parameters(RequestParameters v) { r.parameters = v; return this; }
        public Builder attributes(RequestAttributes v) { r.attributes = v; return this; }
        public Builder body(byte[] v) { r.body = v; return this; }
        public Builder requestTime(LocalDateTime v) { r.requestTime = v; return this; }
        public HttpServletRequest build() { return r; }
    }
}
