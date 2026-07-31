package com.chua.protocol.support.network.protocol.server;

import com.chua.protocol.support.network.protocol.request.ServletRequest;
import com.chua.protocol.support.network.protocol.request.ServletResponse;

/**
 * Filter chain stub.
 * @author CH
 */
public interface ServletFilterChain {
    void doFilter(ServletRequest request, ServletResponse response) throws Exception;
}
