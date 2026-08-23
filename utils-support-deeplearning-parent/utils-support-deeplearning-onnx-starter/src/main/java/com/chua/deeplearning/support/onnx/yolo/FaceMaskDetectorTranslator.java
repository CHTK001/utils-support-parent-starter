package com.chua.deeplearning.support.onnx.yolo;

import java.util.Arrays;
import java.util.List;

public class FaceMaskDetectorTranslator extends YoloTranslator {

    private static final List<String> FACE_MASK_2_CLASSES = Arrays.asList("cloth", "surgical");

    public FaceMaskDetectorTranslator() {
        super(640, 0.05f, 0.50f, FACE_MASK_2_CLASSES, true);
    }

    @Override
    protected String getYoloVersion() {
        return "YOLO-FaceMask";
    }
}