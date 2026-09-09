package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.protocol.event.ServletEvent;
import com.chua.common.support.network.protocol.event.ServletListener;
import com.chua.common.support.network.protocol.event.ServletRequestStatus;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.ServletListenerRegistry;
import com.chua.common.support.network.protocol.server.UpgradeServletFilter;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 统计过滤器：统计成功/失败/拒绝/总耗时等
 *
 * 作为过滤器以便通过SPI加载，同时注册为全局监听器
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
@Spi("statistic")
@SpiDescribe("统计过滤器")
public class StatisticServletFilter extends UpgradeServletFilter<Object> implements ServletListener {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong failure = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong totalCost = new AtomicLong();

    private final Map<Integer, AtomicLong> statusBuckets = new ConcurrentHashMap<>();

    @Override
    public void init(com.chua.common.support.network.protocol.server.ServletFilterConfig config) throws Exception {
        super.init(config);
        ServletListenerRegistry.registerGlobal(this);
    }

    @Override
    public void destroy() {
        ServletListenerRegistry.unregisterGlobal(this);
        super.destroy();
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response, ServletFilterChain chain,
                                            Object configObject) throws Exception {
        // 统计过滤器不改变链路
        chain.doFilter(request, response);
    }

    @Override
    public void onEvent(ServletEvent event) {
        if (event == null) return;
        total.incrementAndGet();
        if (event.getDuration() > 0) {
            totalCost.addAndGet(event.getDuration());
        }
        if (event.getStatus() == ServletRequestStatus.SUCCESS) success.incrementAndGet();
        else if (event.getStatus() == ServletRequestStatus.REJECTED) rejected.incrementAndGet();
        else if (event.getStatus() == ServletRequestStatus.FAILURE) failure.incrementAndGet();
        if (event.getStatusCode() != null) {
            statusBuckets.computeIfAbsent(event.getStatusCode(), k -> new AtomicLong()).incrementAndGet();
        }
    }

    public long qpsWindow(long seconds) {
        // 简化：返回总数，具体QPS可在外层按窗口计算
        return total.get();
    }

    public long avgCost() {
        long t = total.get();
        return t == 0 ? 0 : totalCost.get() / t;
    }

    @Override
    public boolean supportProtocol(String protocol) {
        return true; // 支持所有协议
    }
}

