package com.chua.deeplearning.support.onnx.yolo;

import java.util.Arrays;
import java.util.List;

public class SafetyHelmetDetectorTranslator extends YoloTranslator {

    private static final List<String> SAFETY_HELMET_3_CLASSES = Arrays.asList("person", "head", "helmet");

    public SafetyHelmetDetectorTranslator() {
        super(640, 0.05f, 0.50f, SAFETY_HELMET_3_CLASSES, true);
    }

    @Override
    protected String getYoloVersion() {
        return "YOLO-SafetyHelmet";
    }
}