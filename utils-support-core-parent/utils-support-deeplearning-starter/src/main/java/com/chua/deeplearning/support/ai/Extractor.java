package com.chua.deeplearning.support.ai;

import com.chua.deeplearning.support.ai.result.PredictResultObject;

/**
 * 特征提取器接口。
 * <p>
 * 输入任意对象，输出预测结果对象。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Extractor extends AutoCloseable {

    /**
     * extract。
     *
     * @param input 方法入参 input
     * @return Predict结果对象 对象
     */
    PredictResultObject<?> extract(Object input);

    @Override
    /** 关闭 */
    default void close() throws Exception {
    }
}
