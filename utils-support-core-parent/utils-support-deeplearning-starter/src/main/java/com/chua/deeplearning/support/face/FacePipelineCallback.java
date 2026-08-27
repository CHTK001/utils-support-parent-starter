package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.model.PredictRectangle;

import java.util.List;

/**
 * 人脸管线回调接口，用于测试时捕获各阶段中间图片。
 *
 * <p>所有方法均为 default 实现（no-op），测试时按需覆写需要观察的阶段即可。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FacePipelineCallback {

    /**
     * 检测完成回调。
     *
     * @param imageData 原始场景图
     * @param boxes     检测到的人脸框列表
     */
    default void onDetect(byte[] imageData, List<PredictRectangle> boxes) {
    }

    /**
     * 人脸对齐完成回调。
     *
     * @param faceIndex 人脸索引（从0开始）
     * @param box       人脸框
     * @param aligned   对齐后的512×512人脸图
     */
    default void onAlign(int faceIndex, PredictRectangle box, byte[] aligned) {
    }

    /**
     * 人脸修复完成回调。
     *
     * @param faceIndex 人脸索引
     * @param restored  修复后的512×512人脸图
     */
    default void onRestore(int faceIndex, byte[] restored) {
    }

    /**
     * 人脸分割mask生成回调。
     *
     * @param faceIndex 人脸索引
     * @param mask      分割mask（512×512灰度）
     */
    default void onMask(int faceIndex, byte[] mask) {
    }

    /**
     * 人脸贴回原图完成回调。
     *
     * @param faceIndex 人脸索引
     * @param pasted    贴回后的完整场景图
     */
    default void onPaste(int faceIndex, byte[] pasted) {
    }

    /**
     * 管线执行完成回调。
     *
     * @param imageData 原始场景图
     * @param result    最终结果图
     * @param faceCount 处理的人脸数
     * @param elapsedMs 耗时毫秒
     */
    default void onComplete(byte[] imageData, byte[] result, int faceCount, long elapsedMs) {
    }
}
