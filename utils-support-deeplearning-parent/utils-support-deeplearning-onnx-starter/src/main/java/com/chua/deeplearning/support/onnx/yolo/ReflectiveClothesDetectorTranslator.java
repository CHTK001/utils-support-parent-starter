package com.chua.deeplearning.support.onnx.yolo;

import java.util.Arrays;
import java.util.List;

public class ReflectiveClothesDetectorTranslator extends YoloTranslator {

    private static final List<String> REFLECTIVE_CLOTHES_2_CLASSES = Arrays.asList("safe", "unsafe");

    public ReflectiveClothesDetectorTranslator() {
        super(640, 0.05f, 0.50f, REFLECTIVE_CLOTHES_2_CLASSES, true);
    }

    @Override
    protected String getYoloVersion() {
        return "YOLO-ReflectiveClothes";
    }
}