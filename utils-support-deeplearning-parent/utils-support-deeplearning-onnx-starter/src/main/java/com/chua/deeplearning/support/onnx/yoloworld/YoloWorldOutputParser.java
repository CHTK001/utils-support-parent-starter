package com.chua.deeplearning.support.onnx.yoloworld;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.feature.FeatureExtractor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 list 解析 YOLO-World 输出 [1, 9, 8400] -> DetectedObjects。
 */
@Slf4j
public class YoloWorldOutputParser {

    public static DetectedObjects parse(NDList list, List<String> labels, double threshold, float nmsThreshold, int origW, int origH, int inputSize) {
        NDArray out = list.singletonOrThrow();
        float[] data = out.toFloatArray();
        // shape [1, 9, 8400] where 9 = 5 classes + 4 box? 实际需验证
        long[] shape = out.getShape().getShape();
        int dim1 = (int) shape[1];
        int n = (int) shape[2];
        int numClasses = labels.size();
        float scaleX = (float) origW / inputSize;
        float scaleY = (float) origH / inputSize;
        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float maxScore = Float.NEGATIVE_INFINITY;
            int best = -1;
            for (int c = 0; c < numClasses; c++) {
                float s = data[i * dim1 + c]; // 假设通道优先
                if (s > maxScore) { maxScore = s; best = c; }
            }
            if (maxScore < threshold || best < 0) continue;
            float x1 = data[i * dim1 + numClasses] * scaleX;
            float y1 = data[i * dim1 + numClasses + 1] * scaleY;
            float x2 = data[i * dim1 + numClasses + 2] * scaleX;
            float y2 = data[i * dim1 + numClasses + 3] * scaleY;
            float w = x2 - x1, h = y2 - y1;
            if (w <= 0 || h <= 0) continue;
            double rx = x1 / origW, ry = y1 / origH, rw = w / origW, rh = h / origH;
            boxes.add(new Rectangle(rx, ry, rw, rh));
            names.add(labels.get(best));
            probs.add((double) maxScore);
        }
        log.info("[YOLO-World] parse 出 {} 框", names.size());
        return new DetectedObjects(names, probs, boxes);
    }
}
