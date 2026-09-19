package com.chua.collapse.support;

import com.chua.common.support.concurrent.collapse.CollapseBatchFunction;
import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.concurrent.collapse.CollapseExecutor;
import com.chua.common.support.concurrent.collapse.CollapseExecutorFactory;
import com.chua.common.support.concurrent.collapse.CollapseResultMapper;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 默认折叠执行器工厂。
 *
 * <p>基于虚拟线程承载收集调度与批量执行的默认实现，通过 SPI 机制注册
 * （{@code @Spi("collapse")}），使用方可通过服务发现获取本工厂并创建折叠执行器。</p>
 *
 * <p>支持两种折叠模式：</p>
 * <ul>
 *   <li>广播模式：{@link #create(CollapseConfig, CollapseBatchFunction)}，按入参 equals 分组，组内广播同一结果</li>
 *   <li>拆分回填模式：{@link #create(CollapseConfig, CollapseResultMapper)}，整批合并执行后按调用者回填各自结果
 *       （配合 {@link CollapseConfig#setMergeAll(boolean)} 使用）</li>
 * </ul>
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

    @Override
    public <INPUT, OUTPUT> CollapseExecutor<INPUT, OUTPUT> create(CollapseConfig config,
                                                                  CollapseResultMapper<INPUT, OUTPUT> resultMapper) {
        return new DefaultCollapseExecutor<>(config, resultMapper);
    }
}
