package com.chua.common.support.scattergather;

import java.util.List;

/**
 * 聚合器。
 * <p>将多个节点的查询结果聚合成最终结果。</p>
 *
 * @param <T> 数据类型
 * @author CH
 * @since 4.0.0.42
 */
public interface ScatterGatherAggregator<T> {

    /**
     * 聚合结果。
     *
     * @param context 查询上下文
     * @param results 节点结果列表
     * @return 聚合后的结果
     */
    T aggregate(ScatterGatherContext context, List<ScatterGatherResult<T>> results);
}
