package com.chua.deeplearning.support.recognition;

/**
 * 版面分析管线回调接口，用于测试时捕获各阶段中间数据。
 *
 * <p>所有方法均为 default 实现（no-op），测试时按需覆写需要观察的阶段即可。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface LayoutPipelineCallback {

    /**
     * 预处理完成回调。
     *
     * @param imageData 原始图像
     * @param processed 预处理后的图像
     */
    default void onPreprocess(byte[] imageData, byte[] processed) {
    }

    /**
     * 版面识别完成回调。
     *
     * @param result 版面识别结果
     */
    default void onRecognize(Object result) {
    }
}
