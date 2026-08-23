package com.chua.datasource.support.engine;

import com.chua.common.support.spi.annotations.Spi;

/**
 * 内存响应式引擎，对应同步侧 {@link InMemoryEngine}。
 *
 * <p>通过 {@link DefaultReactorEngine} 将同步 InMemoryEngine 包装为响应式，
 * 所有阻塞调用通过 {@code boundedElastic} 调度执行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("memory")
public class InMemoryReactorEngine extends DefaultReactorEngine {

    /**
     * 创建内存响应式引擎，内部持有同步 {@link InMemoryEngine}。
     */
    public InMemoryReactorEngine() {
        super(new InMemoryEngine());
    }
}