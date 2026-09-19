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

    /**
     * detect。
     *
     * @param input 方法入参 input
     * @return Predict结果对象 对象
     */
    PredictResultObject<?> detect(Object input);

    @Override
    /** 关闭 */
    default void close() throws Exception {
    }
}
