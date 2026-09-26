package com.chua.deeplearning.support.utils;

/**
 * 像素矩形裁剪选项。
 *
 * @param imageData 图像字节
 * @param x         左边界
 * @param y         上边界
 * @param width     宽度
 * @param height    高度
 * @author CH
 * @since 4.0.0.42
 */
public record ImageCropOptions(
        byte[] imageData,
        int x,
        int y,
        int width,
        int height
) {

    /**
     * 规范构造器：图像字节做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。仅按坐标裁剪时调用方会显式传
     * {@code null}（图像已解为 {@code Mat}），故保留其 {@code null} 语义。</p>
     *
     * @param imageData 图像字节
     */
    public ImageCropOptions {
        imageData = imageData == null ? null : imageData.clone();
    }

    /**
     * 访问器覆写：返回图像字节的副本。
     *
     * @return 图像字节副本；未传则返回 {@code null}
     */
    @Override
    public byte[] imageData() {
        return imageData == null ? null : imageData.clone();
    }
}
