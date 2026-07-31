package com.chua.protocol.support.network.protocol.server;

import com.chua.protocol.support.network.protocol.filter.ServletFilter;
import com.chua.protocol.support.network.protocol.request.ServletRequest;
import com.chua.protocol.support.network.protocol.request.ServletResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Default filter chain stub.
 * @author CH
 */
public class DefaultServletFilterChain implements ServletFilterChain {
    private final List<ServletFilter> filters;
    /**
     * 索引名
     */
    private int index;

    public DefaultServletFilterChain(ServletFilter... filters) {
        this.filters = new ArrayList<>();
        if (filters != null) {
            this.filters.addAll(Arrays.asList(filters));
            this.filters.sort(Comparator.comparingInt(ServletFilter::getOrder));
        }
    }

    public DefaultServletFilterChain(List<ServletFilter> filters) {
        this.filters = filters == null ? new ArrayList<>() : new ArrayList<>(filters);
        this.filters.sort(Comparator.comparingInt(ServletFilter::getOrder));
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws Exception {
        if (index >= filters.size()) {
            return;
        }
        ServletFilter filter = filters.get(index++);
        filter.doFilter(request, response, this);
    }
}
