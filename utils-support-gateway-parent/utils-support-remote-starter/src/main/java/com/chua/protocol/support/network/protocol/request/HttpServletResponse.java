package com.chua.protocol.support.network.protocol.request;

/**
 * HTTP servlet response stub.
 * @author CH
 */
public class HttpServletResponse implements ServletResponse {
    private String requestId;
    private int statusCode;
    private String statusMessage;
    /**
     * 是否成功
     */
    private boolean success;
    private String contentType;
    private long contentLength;
    /**
     * 请求体
     */
    private byte[] body;
    private RequestHeaders headers;
    private boolean terminateEarly;

    public static Builder builder() {
        return new Builder();
    }

    public static HttpServletResponse from(ServletResponse response) {
        if (response instanceof HttpServletResponse httpServletResponse) {
            return httpServletResponse;
        }
        Builder b = builder();
        if (response != null) {
            b.statusCode(response.getStatusCode())
                    .headers(response.getHeaders())
                    .body(response.getBody())
                    .contentType(response.getContentType());
        } else {
            b.statusCode(200).statusMessage("OK").headers(new RequestHeaders());
        }
        return b.build();
    }

    @Override
    public int getStatusCode() {
        return statusCode;
    }

    @Override
    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    @Override
    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    @Override
    public void setSuccess(boolean success) {
        this.success = success;
    }

    @Override
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public void setContentLength(long contentLength) {
        this.contentLength = contentLength;
    }

    @Override
    public void setBody(byte[] body) {
        this.body = body;
    }

    @Override
    public byte[] getBody() {
        return body;
    }

    @Override
    public RequestHeaders getHeaders() {
        return headers;
    }

    @Override
    public void setHeaders(RequestHeaders headers) {
        this.headers = headers;
    }

    @Override
    public void setTerminateEarly(boolean terminateEarly) {
        this.terminateEarly = terminateEarly;
    }

    public static final class Builder {
        private final HttpServletResponse r = new HttpServletResponse();

        public Builder requestId(String v) { r.requestId = v; return this; }
        public Builder statusCode(int v) { r.statusCode = v; return this; }
        public Builder statusMessage(String v) { r.statusMessage = v; return this; }
        public Builder success(boolean v) { r.success = v; return this; }
        public Builder contentType(String v) { r.contentType = v; return this; }
        public Builder contentLength(long v) { r.contentLength = v; return this; }
        public Builder body(byte[] v) { r.body = v; return this; }
        public Builder headers(RequestHeaders v) { r.headers = v; return this; }
        public Builder terminateEarly(boolean v) { r.terminateEarly = v; return this; }
        public HttpServletResponse build() { return r; }
    }
}
