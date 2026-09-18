package com.chua.deeplearning.support.ocr;

import com.chua.deeplearning.support.model.PredictRectangle;

import java.util.List;

/**
* OCR 管线回调接口，用于测试时捕获各阶段中间数据。
*
* <p>所有方法均为 default 实现（no-op），测试时按需覆写需要观察的阶段即可。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface OcrPipelineCallback {

    /**
    * 检测完成回调。
    *
    * @param imageData 原始场景图
    * @param boxes     检测到的文字框列表
    */
    default void onDetect(byte[] imageData, List<PredictRectangle> boxes) {
    }

    /**
    * 文字识别完成回调。
    *
    * @param box     文字框
    * @param text    识别文字
    * @param conf    置信度
    * @param index   当前索引
    * @param total   总数
    */
    default void onRecognize(PredictRectangle box, String text, float conf, int index, int total) {
    }

    /**
    * 方向矫正完成回调。
    *
    * @param corrected 矫正后的图片
    */
    default void onCorrect(byte[] corrected) {
    }

    /**
    * 文字高清化完成回调。
    *
    * @param enhanced 高清化后的图片
    */
    default void onEnhance(byte[] enhanced) {
    }
}
