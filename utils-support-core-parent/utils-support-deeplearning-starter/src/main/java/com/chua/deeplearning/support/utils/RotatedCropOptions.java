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

    /**
     * 规范构造器：原图字节做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。原图字节仅被解码读取，
     * 允许安全拷贝；保留其 {@code null} 语义。</p>
     *
     * @param imageData 原图字节
     */
    public RotatedCropOptions {
        imageData = imageData == null ? null : imageData.clone();
    }

    /**
     * 访问器覆写：返回原图字节的副本。
     *
     * @return 原图字节副本；无则返回 {@code null}
     */
    @Override
    public byte[] imageData() {
        return imageData == null ? null : imageData.clone();
    }
}
