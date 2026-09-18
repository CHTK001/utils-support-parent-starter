package com.chua.deeplearning.support.recognition;

import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.plate.PlateResult;

import java.util.List;

/**
* 车牌号识别管线回调接口，用于测试时捕获各阶段中间数据。
*
* <p>所有方法均为 default 实现（no-op），测试时按需覆写需要观察的阶段即可。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface PlateNumberPipelineCallback {

    /**
    * 检测完成回调。
    *
    * @param imageData 原始场景图
    * @param boxes     检测到的车牌框列表
    */
    default void onDetect(byte[] imageData, List<PredictRectangle> boxes) {
    }

    /**
    * 车牌识别完成回调。
    *
    * @param box    车牌框
    * @param result 车牌识别结果
    * @param index  当前索引
    * @param total  总数
    */
    default void onRecognize(PredictRectangle box, PlateResult result, int index, int total) {
    }
}
