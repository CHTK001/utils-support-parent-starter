package com.chua.common.support.scattergather;

import java.util.List;

/**
 * 默认聚合器。
 * <p>返回第一个成功结果。</p>
 *
 * @param <T> 数据类型
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultScatterGatherAggregator<T> implements ScatterGatherAggregator<T> {

    @Override
    public T aggregate(ScatterGatherContext context, List<ScatterGatherResult<T>> results) {
        for (ScatterGatherResult<T> result : results) {
            if (result != null && result.isSuccess()) {
                return result.getData();
            }
        }
        return null;
    }
}
