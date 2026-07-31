package com.chua.protocol.support.network.protocol.request;

/**
 * Servlet response stub.
 * @author CH
 */
public interface ServletResponse extends AutoCloseable {
    int getStatusCode();

    void setStatusCode(int statusCode);

    void setStatusMessage(String statusMessage);

    void setSuccess(boolean success);

    void setContentType(String contentType);

    String getContentType();

    void setContentLength(long contentLength);

    void setBody(byte[] body);

    byte[] getBody();

    RequestHeaders getHeaders();

    void setHeaders(RequestHeaders headers);

    void setTerminateEarly(boolean terminateEarly);

    @Override
    default void close() {
    }
}
