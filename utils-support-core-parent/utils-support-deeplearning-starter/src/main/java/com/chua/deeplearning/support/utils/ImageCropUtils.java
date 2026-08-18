package com.chua.deeplearning.support.utils;

import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;

/**
 * 图片裁剪工具（基于检测框），统一委托 {@link ImageUtils}。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ImageCropUtils {

    /**
     * 私有构造，防止实例化。
     */
    private ImageCropUtils() {
    }

    /**
     * 按像素框裁剪。
     *
     * @param imageData 原图
     * @param x         左
     * @param y         上
     * @param width     宽
     * @param height    高
     * @return 裁剪图 PNG 字节
     */
    public static byte[] crop(byte[] imageData, int x, int y, int width, int height) {
        return ImageUtils.crop(imageData, x, y, width, height);
    }

    /**
     * 按 {@link PredictRectangle} 裁剪。
     * <p>宽高 &lt;= 1 时按归一化坐标处理，否则按像素。</p>
     *
     * @param imageData 原图
     * @param box       框
     * @return 裁剪图
     */
    public static byte[] crop(byte[] imageData, PredictRectangle box) {
        if (box == null) {
            return imageData;
        }
        return ImageUtils.cropNormalizedOrPixel(imageData, box.x(), box.y(), box.width(), box.height());
    }

    /**
     * 按 {@link DetectionInfo} 裁剪。
     *
     * @param imageData 原图
     * @param info      检测结果
     * @return 裁剪图
     */
    public static byte[] crop(byte[] imageData, DetectionInfo info) {
        if (info == null) {
            return imageData;
        }
        return ImageUtils.cropNormalizedOrPixel(imageData, info.x(), info.y(), info.width(), info.height());
    }
}