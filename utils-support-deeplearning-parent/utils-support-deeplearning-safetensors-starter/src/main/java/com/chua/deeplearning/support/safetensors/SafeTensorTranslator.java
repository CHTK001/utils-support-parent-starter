package com.chua.deeplearning.support.safetensors;

import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
public class SafeTensorTranslator implements ITranslator<byte[], List<PredictRectangle>> {

    private final SafeTensorServiceClient client;
    private final String modelName;
    private final String modelType;

    public SafeTensorTranslator(String host, int port) {
        this(host, port, "facade-face", "face_detection");
    }

    public SafeTensorTranslator(String host, int port, String modelName, String modelType) {
        this.client = new SafeTensorServiceClient(host, port);
        this.modelName = modelName;
        this.modelType = modelType;
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    public List<PredictRectangle> translate(byte[] input) {
        if (input == null || input.length == 0) {
            return List.of();
        }
        try {
            Map<String, Object> result = client.infer(
                    modelName,
                    modelType,
                    Map.of("image", input),
                    Map.of()
            );

            if (result == null) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> faces = (List<Map<String, Object>>) result.get("faces");
            if (faces == null) {
                return List.of();
            }
            return faces.stream().map(faceMap -> {
                float confidence = ((Number) faceMap.getOrDefault("confidence", 0f)).floatValue();
                float x = ((Number) faceMap.getOrDefault("x", 0f)).floatValue();
                float y = ((Number) faceMap.getOrDefault("y", 0f)).floatValue();
                float width = ((Number) faceMap.getOrDefault("width", 0f)).floatValue();
                float height = ((Number) faceMap.getOrDefault("height", 0f)).floatValue();
                int label = ((Number) faceMap.getOrDefault("label", 0)).intValue();
                String labelName = (String) faceMap.getOrDefault("labelName", "face");
                return new PredictRectangle(x, y, width, height, confidence, label, labelName);
            }).toList();
        } catch (Exception e) {
            log.error("SafeTensor 翻译失败: {}", e.getMessage(), e);
            return List.of();
        }
    }
}
