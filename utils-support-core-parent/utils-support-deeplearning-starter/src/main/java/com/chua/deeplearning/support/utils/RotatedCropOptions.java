package com.chua.deeplearning.support.utils;

/**
 * 旋转矩形裁剪选项。
 *
 * <p>绕旋转矩形中心反旋转 angle，将倾斜文字扶正为水平，
 * 再输出 rw x rh 水平矩形区域（多余部分白底填充）。</p>
 *
 * @param imageData 原图字节
 * @param cx        旋转矩形中心 x
 * @param cy        旋转矩形中心 y
 * @param rw        旋转矩形宽度（长边）
 * @param rh        旋转矩形高度（短边）
 * @param angle     旋转角度（度）
 * @author CH
 * @since 4.0.0.42
 */
public record RotatedCropOptions(
        byte[] imageData,
        double cx,
        double cy,
        double rw,
        double rh,
        double angle
) {
}
