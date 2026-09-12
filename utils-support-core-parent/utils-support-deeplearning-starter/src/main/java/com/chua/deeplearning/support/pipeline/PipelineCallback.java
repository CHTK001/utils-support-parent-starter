package com.chua.deeplearning.support.pipeline;

/**
 * 管线通用回调接口，用于调试时捕获各阶段中间数据。
 * <p>
   * 所有方法均为 默认 实现（no-op），按需覆写需要观察的阶段即可。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface PipelineCallback {

    /**
     * 管线步骤执行回调。
     *
     * @param stepName  步骤名称（如 "detect", "recognize", "align"）
     * @param stageData 该步骤的中间数据（图像字节、文本等）
     */
    default void onStep(String stepName, Object stageData) {
    }

    /**
     * 管线步骤执行回调（带图片索引）。
     *
     * @param stepName  步骤名称
     * @param index     多实例索引（如第几张人脸、第几行文字）
     * @param stageData 该步骤的中间数据
     */
    default void onStep(String stepName, int index, Object stageData) {
    }

    /**
     * 管线完成回调。
     *
     * @param result    最终结果
     * @param elapsedMs 耗时毫秒
     */
    default void onComplete(Object result, long elapsedMs) {
    }

    /**
     * 管线异常回调。
     *
     * @param stepName 异常发生的步骤
     * @param error    异常信息
     */
    default void onError(String stepName, String error) {
    }
}