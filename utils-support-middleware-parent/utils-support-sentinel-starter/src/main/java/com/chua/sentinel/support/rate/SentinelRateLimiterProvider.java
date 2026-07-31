package com.chua.sentinel.support.rate;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.chua.common.support.concurrent.rate.RateLimiterProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Alibaba Sentinel 的限流提供者实现。
 *
 * <p>使用 Sentinel {@link SphU} 提供企业级流量控制能力。</p>
 *
 * @author CH
 */
@Spi("sentinel")
public class SentinelRateLimiterProvider implements RateLimiterProvider {

    /**
     * 限流器名称
     */
    private final String name;

    /**
     * Sentinel 资源名称
     */
    private final String resourceName;

    public SentinelRateLimiterProvider() {
        this("sentinel-limiter");
    }

    public SentinelRateLimiterProvider(String name) {
        this.name = name;
        this.resourceName = name;
    }

    @Override
    public boolean tryAcquire() {
        try (Entry ignored = SphU.entry(resourceName)) {
            return true;
        } catch (BlockException e) {
            return false;
        }
    }

    @Override
    public boolean tryAcquire(long timeout, TimeUnit timeUnit) {
        long deadline = System.currentTimeMillis() + timeUnit.toMillis(timeout);
        for (;;) {
            try (Entry ignored = SphU.entry(resourceName)) {
                return true;
            } catch (BlockException e) {
                if (System.currentTimeMillis() >= deadline) {
                    return false;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    @Override
    public String getName() {
        return name;
    }
}