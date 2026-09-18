package com.chua.deeplearning.support.onnx.yolo;

import java.util.Arrays;
import java.util.List;
import com.chua.deeplearning.support.ai.DetectionConfiguration;
/**
* SafetyHelmetDetectorTranslator类。
*
* @author CH
* @since 4.0.0
 */

public class SafetyHelmetDetectorTranslator extends YoloTranslator {

    private static final List<String> SAFETY_HELMET_3_CLASSES = Arrays.asList("person", "head", "helmet"); // safetyhelmet3类

    /**
    * safetyhelmetdetectortranslator。
    */
    public SafetyHelmetDetectorTranslator() {
        super(640, 0.05f, 0.50f, SAFETY_HELMET_3_CLASSES, true);
    }

    @Override
    protected String getYoloVersion() {
        return "YOLO-SafetyHelmet";
    }
    /**
    * 创建 Translator（支持外部阈值覆盖，未提供时使用内置默认值）。
    *
    * @param configuration 检测配置（可空）
    */
    public SafetyHelmetDetectorTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        super(640,
                configuration == null ? 0.05f
                        : configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD, 0.05f),
                configuration == null ? 0.50f
                        : configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_IOU_THRESHOLD, 0.50f),
                SAFETY_HELMET_3_CLASSES, true);
    }

}
