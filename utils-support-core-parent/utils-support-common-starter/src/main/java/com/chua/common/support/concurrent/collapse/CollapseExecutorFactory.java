package com.chua.common.support.concurrent.collapse;

/**
 * 折叠执行器工厂 SPI 接口。
 *
 * <p>折叠执行器的具体实现由各实现模块提供，并通过 SPI 机制（{@code @Spi} 注解）注册。
 * 使用方通过本接口创建绑定批量执行函数的折叠执行器实例，无需感知底层收集与调度细节。</p>
 *
 * <p>实现约定：</p>
 * <ul>
 *   <li>实现类需提供无参构造器，并标注 {@code @Spi} 注解以支持服务发现</li>
 *   <li>{@link #create(CollapseConfig, CollapseBatchFunction)} 返回的执行器实例在创建时
 *       应完成线程模型（虚拟线程/平台线程）、收集器（CAS 单收集者）等初始化</li>
 * </ul>
 *
 * @author CH
 * @since 2026/09/03
 */
public interface CollapseExecutorFactory {

    /**
     * 创建折叠执行器。
     *
     * @param config        折叠配置，不可为空
     * @param batchFunction 批量执行函数，不可为空
     * @param <INPUT>       单次调用的入参类型
     * @param <OUTPUT>      批量返回类型
     * @return 折叠执行器实例
     */
    <INPUT, OUTPUT> CollapseExecutor<INPUT, OUTPUT> create(CollapseConfig config,
                                                           CollapseBatchFunction<INPUT, OUTPUT> batchFunction);
}
