package com.chua.protocol.support.network.protocol.filter;

import com.chua.protocol.support.network.protocol.request.ServletRequest;
import com.chua.protocol.support.network.protocol.request.ServletResponse;
import com.chua.protocol.support.network.protocol.server.ServletFilterChain;

/**
 * Servlet filter SPI stub.
 * @author CH
 */
public interface ServletFilter {
    void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception;

    String getFilterId();

    String getFilterName();

    int getOrder();
}
