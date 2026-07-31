package com.chua.deeplearning.support.ai;

import com.chua.deeplearning.support.ai.result.PredictResultObject;

/**
 * 检测器接口。
 * <p>
 * 输入任意对象，输出检测结果对象。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Detector extends AutoCloseable {

    PredictResultObject<?> detect(Object input);

    @Override
    default void close() throws Exception {
    }
}
