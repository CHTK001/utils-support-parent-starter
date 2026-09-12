package com.chua.deeplearning.support.onnx.yolo;

import java.util.Arrays;
import java.util.List;
import com.chua.deeplearning.support.ai.DetectionConfiguration;
/**
* ReflectiveClothesDetectorTranslator类。
*
* @author CH
* @since 4.0.0
 */

public class ReflectiveClothesDetectorTranslator extends YoloTranslator {

    private static final List<String> REFLECTIVE_CLOTHES_2_CLASSES = Arrays.asList("safe", "unsafe"); // reflectiveclothes2类

    /**
    * reflectiveclothesdetectortranslator。
     */
    public ReflectiveClothesDetectorTranslator() {
        super(640, 0.05f, 0.50f, REFLECTIVE_CLOTHES_2_CLASSES, true);
    }

    @Override
    protected String getYoloVersion() {
        return "YOLO-ReflectiveClothes";
    }
    /**
    * 创建 Translator（支持外部阈值覆盖，未提供时使用内置默认值）。
    *
    * @param configuration 检测配置（可空）
     */
    public ReflectiveClothesDetectorTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        super(640,
                configuration == null ? 0.05f
                        : configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD, 0.05f),
                configuration == null ? 0.50f
                        : configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_IOU_THRESHOLD, 0.50f),
                REFLECTIVE_CLOTHES_2_CLASSES, true);
    }

}