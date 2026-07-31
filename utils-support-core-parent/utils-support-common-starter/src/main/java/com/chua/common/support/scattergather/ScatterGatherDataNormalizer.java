package com.chua.common.support.scattergather;

/**
 * 将业务输入标准化为统一聚合实体。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author CH
 */
public interface ScatterGatherDataNormalizer<I, O> {

    /**
     * 标准化数据。
     *
     * @param context 上下文
     * @param input   输入数据
     * @return 标准化后的数据
     */
    O normalize(ScatterGatherContext context, I input);
}
