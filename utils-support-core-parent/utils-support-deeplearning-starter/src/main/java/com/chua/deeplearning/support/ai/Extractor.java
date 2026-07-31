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

    PredictResultObject<?> extract(Object input);

    @Override
    default void close() throws Exception {
    }
}
