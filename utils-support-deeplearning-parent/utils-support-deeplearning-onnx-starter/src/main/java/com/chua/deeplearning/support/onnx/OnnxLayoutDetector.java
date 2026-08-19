package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.layout.LayoutDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxLayoutDetector implements LayoutDetector {

    /** 模型名称 */
    private String modelName;
    /** 阈值 */
    private float threshold = 0.5f;
    /** 模型路径 */
    private String modelPath;
    /** 是否使用 GPU */
    private boolean useGpu = false;
    /** 设备类型 */
    private String device = "cpu";

    public OnnxLayoutDetector(String apiKey) {
    }

    @Override
    public LayoutDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "doc-layout-yolo-imgsz640";
    }

    @Override
    public LayoutDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    public LayoutDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public LayoutDetector useGpu(boolean useGpu) {
        this.useGpu = useGpu;
        return this;
    }

    @Override
    public LayoutDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public Map<String, List<PredictRectangle>> detect(byte[] imageData) {
        List<DetectionInfo> detections = ImageDetector.create(resolveModel())
                .threshold(threshold).modelPath(modelPath).device(device).detect(imageData);
        Map<String, List<PredictRectangle>> result = new LinkedHashMap<>();
        for (DetectionInfo d : detections) {
            String label = d.label() == null || d.label().isBlank() ? "unknown" : d.label();
            result.computeIfAbsent(label, k -> new ArrayList<>())
                  .add(new PredictRectangle(d.x(), d.y(), d.width(), d.height(), d.confidence(), 0, d.label()));
        }
        return result;
    }

    @Override
    public String parse(byte[] imageData) {
        Map<String, List<PredictRectangle>> regions = detect(imageData);
        StringBuilder sb = new StringBuilder();
        for (var entry : regions.entrySet()) {
            for (var rect : entry.getValue()) {
                sb.append(rect.labelName()).append(" ");
            }
        }
        return sb.toString().trim();
    }

}


