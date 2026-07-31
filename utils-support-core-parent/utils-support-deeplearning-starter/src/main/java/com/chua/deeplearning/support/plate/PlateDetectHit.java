package com.chua.deeplearning.support.plate;

import com.chua.deeplearning.support.model.PredictRectangle;

/**
 * 车牌检测命中结果，包含边界框、裁剪图像和识别文字。
 *
 * @param box        检测边界框
 * @param plateImage 车牌裁剪图像
 * @param plateText  识别出的车牌号码
 * @param plateColor 识别出的车牌颜色
 * @author CH
 * @since 4.0.0.42
 */
public record PlateDetectHit(
        PredictRectangle box,
        byte[] plateImage,
        String plateText,
        String plateColor) {
}