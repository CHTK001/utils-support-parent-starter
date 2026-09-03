package com.chua.common.support.concurrent.collapse;

import java.util.Collection;

/**
 * 折叠批量执行函数。
 *
 * <p>声明一次批量调用的执行逻辑：接收同一批次内合并后的全部入参，
 * 执行一次真实的批量处理并返回结果。由折叠执行器在收集到一批调用后回调。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>实现必须保证线程安全，同一实例可能被并发调用</li>
 *   <li>实现应只包含无副作用（只读/幂等）逻辑，折叠会改变方法实际执行次数</li>
 *   <li>返回结果将由执行器按策略广播或回填给本批内的各个调用方</li>
 * </ul>
 *
 * @param <INPUT>  单次调用的入参类型
 * @param <OUTPUT> 批量返回类型
 * @author CH
 * @since 2026/09/03
 */
@FunctionalInterface
public interface CollapseBatchFunction<INPUT, OUTPUT> {

    /**
     * 执行一次批量逻辑。
     *
     * @param inputs 本批次合并后的全部入参，不为空且长度不小于 1
     * @return 批量执行结果
     * @throws Throwable 批量执行过程中发生的异常
     */
    OUTPUT executeBatch(Collection<INPUT> inputs) throws Throwable;
}
