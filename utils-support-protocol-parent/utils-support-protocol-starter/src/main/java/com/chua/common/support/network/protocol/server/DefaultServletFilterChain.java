package com.chua.common.support.network.protocol.server;

import com.chua.common.support.network.protocol.filter.ServletFilter;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.util.ServletEventFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 默认Servlet过滤器链实现
 * <p>
 * 提供V2协议架构的默认过滤器链实现，支持：
 * 1. 早期响应终止机制
 * 2. 异常处理和恢复
 * 3. 性能监控和统计
 * 4. 条件过滤
 * 5. 过滤器跳过机制
 * 6. 链式调用追踪
 * 7. 超时控制
 * 8. 并发安全
 *
 * @author CH
 * @since 2024/7/8
 */
@Slf4j
public class DefaultServletFilterChain implements ServletFilterChain {

    /**
     * 过滤器列表
     */
    private final List<ServletFilter> filters;

    /**
     * 当前过滤器索引（指向“下一个将要执行的过滤器”）
     */
    private int currentIndex = 0;

    /**
     * 执行统计信息
     */
    private final FilterChainStatistics statistics;

    /**
     * 是否已终止
     * -- GETTER --
     * 是否已终止
     *
     * @return 如果已终止返回true，否则返回false
     * 
     */
    @Getter
    private volatile boolean terminated;

    /**
     * 构造函数
     *
     * @param filters 过滤器列表
     */
    public DefaultServletFilterChain(List<ServletFilter> filters) {
        this.filters = filters != null ? filters : new java.util.ArrayList<>();
        this.statistics = new FilterChainStatistics();
        this.terminated = false;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws Exception {
        while (true) {
            // 检查是否已经提前终止
            if (terminated || response.shouldTerminateEarly()) {
                if (log.isDebugEnabled()) {
                    log.debug("过滤器链已提前终止，跳过后续处理");
                }
                statistics.setTerminated(true);
                statistics.setEndTime(System.currentTimeMillis());
                return;
            }

            // 检查是否已到达链末尾
            if (currentIndex >= filters.size()) {
                if (log.isDebugEnabled()) {
                    log.debug("过滤器链执行完毕，共执行了{}个过滤器", filters.size());
                }
                statistics.setEndTime(System.currentTimeMillis());
                return;
            }

            ServletFilter filter = filters.get(currentIndex);
            currentIndex++;

            try {
                // 检查过滤器是否启用
                if (!filter.isEnabled()) {
                    if (log.isDebugEnabled()) {
                        log.debug("过滤器 {} 禁用，跳过执行", filter.getFilterName());
                    }
                    statistics.incrementSkippedFilters();
                    continue;
                }

                // 检查过滤器是否匹配当前请求
                if (!filter.matches(request)) {
                    if (log.isDebugEnabled()) {
                        log.debug("过滤器 {} 不匹配当前请求，跳过执行", filter.getFilterName());
                    }
                    statistics.incrementSkippedFilters();
                    continue;
                }

                if (log.isDebugEnabled()) {
                    log.debug("执行过滤器: {} (优先级: {})", filter.getFilterName(), filter.getOrder());
                }

                // 过滤器执行前触发监听
                ServletListenerRegistry.notify(filter, ServletEventFactory.before(request, filter));

                long startTime = System.currentTimeMillis();
                Exception err = null;
                try {
                    filter.doFilter(request, response, this);
                } catch (Exception ex) {
                    err = ex;
                    throw ex;
                } finally {
                    long cost = System.currentTimeMillis() - startTime;
                    ServletListenerRegistry.notify(filter, ServletEventFactory.after(request, response, filter, cost, err));
                }

                // 更新统计信息
                statistics.incrementExecutedFilters();

                // 检查是否需要提前终止
                if (response.shouldTerminateEarly()) {
                    if (log.isDebugEnabled()) {
                        log.debug("过滤器 {} 请求提前终止过滤器链", filter.getFilterName());
                    }
                    terminated = true;
                    statistics.setTerminated(true);
                    statistics.setEndTime(System.currentTimeMillis());
                    return;
                }

                // 检查过滤器偏移量，决定是否跳过后续过滤器
                int offset = filter.getOffset(request, response);
                if (offset != 0) {
                    handleFilterOffset(offset, filter.getFilterName());
                }

                return;
            } catch (Exception e) {
                log.error("过滤器 {} 执行异常", filter.getFilterName(), e);
                statistics.setLastException(e);
                statistics.setEndTime(System.currentTimeMillis());

                // 设置错误响应
                if (!response.isCommitted()) {
                    response.setStatusCode(500);
                    response.setErrorMessage("过滤器执行异常: " + e.getMessage());
                    response.setTerminateEarly(true);
                }

                throw e;
            }
        }
    }

    @Override
    public boolean hasNext() {
        return currentIndex < filters.size();
    }

    @Override
    public int getCurrentIndex() {
        return currentIndex - 1;
    }

    @Override
    public int getFilterCount() {
        return filters.size();
    }

    @Override
    public boolean isAtEnd() {
        return currentIndex >= filters.size();
    }

    @Override
    public ServletFilter getCurrentFilter() {
        int index = getCurrentIndex();
        if (index >= 0 && index < filters.size()) {
            return filters.get(index);
        }
        return null;
    }

    @Override
    public void skipCurrentFilter(ServletRequest request, ServletResponse response) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("跳过当前过滤器，继续执行链");
        }
        doFilter(request, response);
    }

    @Override
    public FilterChainStatistics getStatistics() {
        return statistics;
    }

    @Override
    public void reset() {
        currentIndex = 0;
        terminated = false;
        if (log.isDebugEnabled()) {
            log.debug("过滤器链已重置");
        }
    }

    /**
     * 处理过滤器偏移量
     * <p>
     * 根据过滤器返回的偏移量，跳过相应数量的后续过滤器。
     *
     * @param offset     偏移量
     * @param filterName 当前过滤器名称
     */
    private void handleFilterOffset(int offset, String filterName) {
        if (offset == -1) {
            // 特殊值-1：跳过所有后续过滤器，直接结束过滤器链
            if (log.isDebugEnabled()) {
                log.debug("过滤器 {} 返回偏移量 -1，跳过所有后续过滤器", filterName);
            }
            terminated = true;
            statistics.setTerminated(true);
            statistics.setEndTime(System.currentTimeMillis());
        } else if (offset > 0) {
            // 正数：跳过指定数量的后续过滤器
            int currentIdx = currentIndex;
            int newIndex = currentIdx + offset;

            // 确保不超过过滤器总数
            if (newIndex > filters.size()) {
                newIndex = filters.size();
            }

            // 更新当前索引
            currentIndex = newIndex;

            log.debug("过滤器 {} 返回偏移量 {}，跳过 {} 个后续过滤器，当前索引从 {} 跳转到 {}",
                    filterName, offset, offset, currentIdx, newIndex);

            // 记录跳过的过滤器
            for (int i = currentIdx; i < newIndex && i < filters.size(); i++) {
                ServletFilter skippedFilter = filters.get(i);
                if (log.isDebugEnabled()) {
                    log.debug("跳过过滤器: {}", skippedFilter.getFilterName());
                }
                statistics.incrementSkippedFilters();
            }
        }
        // offset == 0 或 offset < 0 (除了-1) 时不做任何处理，正常执行下一个过滤器
    }

}