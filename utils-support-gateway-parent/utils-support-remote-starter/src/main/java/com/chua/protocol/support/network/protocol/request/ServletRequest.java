package com.chua.protocol.support.network.protocol.request;

/**
 * Servlet request stub.
 * @author CH
 */
public interface ServletRequest extends AutoCloseable {
    Object getAttribute(String name);

    void setAttribute(String name, Object value);

    String getMethod();

    String getPath();

    String getQueryString();

    String getRequestId();

    byte[] getBody();

    RequestHeaders getHeaders();

    @Override
    default void close() {
    }
}
