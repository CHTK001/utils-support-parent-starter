package com.chua.deeplearning.support.plate;

/**
* 车牌识别结果，包含车牌号码和颜色信息。
*
* @param plateNo    车牌号码
* @param plateColor 车牌颜色
* @author CH
* @since 4.0.0.42
 */
public record PlateResult(
        String plateNo,
        String plateColor) {
}