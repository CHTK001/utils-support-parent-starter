package com.chua.deeplearning.support.recognition;

import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.utils.ImageCropUtils;

import java.util.List;

/**
* 识别管线公共工具。
*
* <p>提供检测结果 → {@link PredictRectangle} 列表的转换，以及按检测框裁剪图像等公共能力，
* 供各识别管线复用。</p>
*
* @author CH
* @since 4.0.0.42
 */
final class RecognitionSupport {

    /** 创建 认可支持 实例 */
    private RecognitionSupport() {
    }

    /**
    * 名称是否包含任一关键字。
    *
    * @param name 名称（小写）
    * @param keys 关键字
    * @return 命中任一返回 true
     */
    static boolean contains(String name, String... keys) {
        if (name == null) {
            return false;
        }
        for (String key : keys) {
            if (name.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
    * 将检测器输出转换为 {@link PredictRectangle} 列表。
    *
    * <p>支持以下输出形态：</p>
    * <ul>
    *   <li>{@code List<PredictRectangle>} 直接返回</li>
    *   <li>DJL {@code DetectedObjects}（经 DjlModelTranslator 已适配为 List）</li>
    *   <li>其它可迭代对象，尽力转换</li>
    * </ul>
    *
    * @param boxes 检测器输出
    * @return 矩形列表
     */
    @SuppressWarnings("unchecked")
    static List<PredictRectangle> toRectangles(Object boxes) {
        if (boxes == null) {
            return List.of();
        }
        if (boxes instanceof List<?> list) {
            if (!list.isEmpty() && list.get(0) instanceof PredictRectangle) {
                return (List<PredictRectangle>) list;
            }
        }
        return List.of();
    }

    /**
    * 按检测框裁剪图像。
    *
    * @param imageData 原始图像
    * @param rect      检测框
    * @return 裁剪后图像
     */
    static byte[] crop(byte[] imageData, PredictRectangle rect) {
        return ImageCropUtils.crop(imageData, rect);
    }
}
