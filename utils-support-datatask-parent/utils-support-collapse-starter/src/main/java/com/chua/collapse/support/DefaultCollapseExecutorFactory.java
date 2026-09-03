package com.chua.collapse.support;

import com.chua.common.support.concurrent.collapse.CollapseBatchFunction;
import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.concurrent.collapse.CollapseExecutor;
import com.chua.common.support.concurrent.collapse.CollapseExecutorFactory;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 默认折叠执行器工厂。
 *
 * <p>基于虚拟线程承载收集调度与批量执行的默认实现，通过 SPI 机制注册
 * （{@code @Spi("collapse")}），使用方可通过服务发现获取本工厂并创建折叠执行器。</p>
 *
 * @author CH
 * @since 2026/09/03
 */
@Spi("collapse")
public class DefaultCollapseExecutorFactory implements CollapseExecutorFactory {

    @Override
    public <INPUT, OUTPUT> CollapseExecutor<INPUT, OUTPUT> create(CollapseConfig config,
                                                                  CollapseBatchFunction<INPUT, OUTPUT> batchFunction) {
        return new DefaultCollapseExecutor<>(config, batchFunction);
    }
}
