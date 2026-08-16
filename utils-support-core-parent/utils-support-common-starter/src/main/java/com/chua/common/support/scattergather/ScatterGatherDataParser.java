package com.chua.common.support.scattergather;


/**
 * 业务原始输入解析器。
 *
 * @param <I> 解析后的数据类型
 * @author CH
 * @since 4.0.0.42
 */
public interface ScatterGatherDataParser<I> {

    /**
     * 解析原始输入。
     *
     * @param context 上下文
     * @param source  原始数据源
     * @return 解析后的数据
     */
    I parse(ScatterGatherContext context, Object source);
}
