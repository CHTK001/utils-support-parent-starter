package com.chua.common.support.concurrent.collapse;

import java.util.Collection;
import java.util.Map;

/**
 * 折叠结果映射器。
 *
 * <p>用于"整批合并执行 + 按调用者拆分回填"场景（配合 {@link CollapseConfig#setMergeAll(boolean)} 使用）：
 * 同一收集批次内的多次调用（各携带自己的集合入参）合并执行一次批量逻辑后，
 * 由映射器为<b>每一个调用者</b>计算出其应得的结果，执行器据此逐个回填。</p>
 *
 * <p>典型场景：并发调用者各自携带小集合（如一批 id），合并为一次批量查询（返回
 * {@code Map<key, 结果>}），随后按各自的集合元素拆分子结果回填。</p>
 *
 * @param <INPUT>  单次调用的入参类型
 * @param <OUTPUT> 单次调用的返回类型
 * @author CH
 * @since 2026/09/03
 */
@FunctionalInterface
public interface CollapseResultMapper<INPUT, OUTPUT> {

    /**
     * 对整批调用执行一次合并逻辑，并返回按调用者划分的结果。
     *
     * @param inputs 本批次的全部调用者入参，不为空
     * @return 调用者入参到其结果的映射；约定包含 {@code inputs} 中的每一个入参
     * @throws Throwable 合并执行过程中发生的异常
     */
    Map<INPUT, OUTPUT> map(Collection<INPUT> inputs) throws Throwable;
}
