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

    /**
    * 创建支持结果拆分回填的折叠执行器。
    *
    * <p>用于"整批合并执行 + 按调用者回填"场景：配置 {@code mergeAll = true} 后，
    * 同一批次内的全部调用合并执行一次，结果由 {@link CollapseResultMapper} 按调用者
    * 拆分后逐项回填。实现模块可按需覆写本方法。</p>
    *
    * @param config   折叠配置，不可为空
    * @param mapper   折叠结果映射器，不可为空
    * @param <INPUT>  单次调用的入参类型
    * @param <OUTPUT> 单次调用的返回类型
    * @return 折叠执行器实例
     */
    default <INPUT, OUTPUT> CollapseExecutor<INPUT, OUTPUT> create(CollapseConfig config,
                                                                   CollapseResultMapper<INPUT, OUTPUT> mapper) {
        throw new UnsupportedOperationException("当前工厂实现不支持结果映射模式");
    }
}
