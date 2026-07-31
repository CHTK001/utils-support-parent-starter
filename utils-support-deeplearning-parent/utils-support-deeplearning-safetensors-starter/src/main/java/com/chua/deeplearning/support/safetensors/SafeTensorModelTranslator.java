package com.chua.deeplearning.support.safetensors;

import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class SafeTensorModelTranslator implements ITranslator<Object, Object> {

    private final SafeTensorServiceClient client;
    private final String modelName;
    private final String modelType;

    public SafeTensorModelTranslator(String host, int port, String modelName, String modelType) {
        this.client = new SafeTensorServiceClient(host, port);
        this.modelName = modelName;
        this.modelType = modelType;
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    public Object translate(Object input) {
        try {
            Map<String, Object> inputMap = buildInput(input);
            Map<String, Object> params = buildParams();
            Map<String, Object> result = client.infer(modelName, modelType, inputMap, params);
            return extractOutput(result);
        } catch (Exception e) {
            log.error("[SafeTensor] 推理失败 [{}:{}]: {}", modelName, modelType, e.getMessage(), e);
            return null;
        }
    }

    private Map<String, Object> buildInput(Object input) {
        if (input instanceof String text) {
            return Map.of("text", text);
        }
        if (input instanceof byte[] bytes) {
            String base64 = Base64.getEncoder().encodeToString(bytes);
            if ("asr".equals(modelType)) {
                return Map.of("audio", base64);
            }
            return Map.of("image", base64);
        }
        if (input instanceof Long id) {
            return Map.of("class_id", id);
        }
        if (input instanceof Integer id) {
            return Map.of("class_id", id.longValue());
        }
        if (input instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stringMap = (Map<String, Object>) map;
            return stringMap;
        }
        if (input != null) {
            return Map.of("input", input.toString());
        }
        return Map.of();
    }

    private Map<String, Object> buildParams() {
        if ("document_ocr".equals(modelType)) {
            return Map.of("max_new_tokens", 2048);
        }
        return Map.of();
    }

    private Object extractOutput(Map<String, Object> result) {
        if (result == null) {
            return null;
        }

        return switch (modelType) {
            case "document_ocr" -> result.get("output");
            case "text_embedding" -> parseEmbedding(result);
            case "face_detection" -> parseFaceDetection(result);
            case "detection", "image_recognition" -> parseDetection(result);
            case "llm", "vlm", "asr", "tts", "ocr" -> {
                Object text = result.get("output");
                if (text == null) text = result.get("text");
                yield text != null ? text.toString() : "";
            }
            case "image_gen", "image_enhance", "matting", "face_swap", "tryon" -> parseImageOutput(result);
            case "music_gen" -> parseAudioOutput(result);
            default -> result;
        };
    }

    private Object parseEmbedding(Map<String, Object> result) {
        Object emb = result.get("embedding");
        if (emb instanceof List<?> list) {
            float[] arr = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = ((Number) list.get(i)).floatValue();
            }
            return arr;
        }
        if (emb instanceof float[] arr) return arr;
        if (emb instanceof double[] arr) {
            float[] f = new float[arr.length];
            for (int i = 0; i < arr.length; i++) f[i] = (float) arr[i];
            return f;
        }
        if (emb instanceof Number n) return new float[]{n.floatValue()};
        return null;
    }

    private Object parseFaceDetection(Map<String, Object> result) {
        Object faces = result.get("faces");
        if (faces instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> toPredictRectangle((Map<String, Object>) item))
                    .toList();
        }
        return List.of();
    }

    private Object parseDetection(Map<String, Object> result) {
        Object items = result.get("items");
        if (items instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> toDetectionInfo((Map<String, Object>) item))
                    .toList();
        }
        Object detections = result.get("detections");
        if (detections instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> toDetectionInfo((Map<String, Object>) item))
                    .toList();
        }
        return List.of();
    }

    private Object parseImageOutput(Map<String, Object> result) {
        Object image = result.get("image");
        if (image instanceof String base64) {
            return Base64.getDecoder().decode(base64);
        }
        if (image instanceof byte[] bytes) return bytes;
        return null;
    }

    private Object parseAudioOutput(Map<String, Object> result) {
        Object audio = result.get("audio");
        if (audio instanceof String base64) {
            return Base64.getDecoder().decode(base64);
        }
        if (audio instanceof byte[] bytes) return bytes;
        return null;
    }

    private PredictRectangle toPredictRectangle(Map<String, Object> faceMap) {
        float confidence = ((Number) faceMap.getOrDefault("confidence", 0f)).floatValue();
        float x = ((Number) faceMap.getOrDefault("x", 0f)).floatValue();
        float y = ((Number) faceMap.getOrDefault("y", 0f)).floatValue();
        float width = ((Number) faceMap.getOrDefault("width", 0f)).floatValue();
        float height = ((Number) faceMap.getOrDefault("height", 0f)).floatValue();
        int label = ((Number) faceMap.getOrDefault("label", 0)).intValue();
        String labelName = (String) faceMap.getOrDefault("labelName", "face");
        return new PredictRectangle(x, y, width, height, confidence, label, labelName);
    }

    private DetectionInfo toDetectionInfo(Map<String, Object> itemMap) {
        String label = (String) itemMap.getOrDefault("label", "unknown");
        float confidence = ((Number) itemMap.getOrDefault("confidence", 0f)).floatValue();
        float x = ((Number) itemMap.getOrDefault("x", 0f)).floatValue();
        float y = ((Number) itemMap.getOrDefault("y", 0f)).floatValue();
        float width = ((Number) itemMap.getOrDefault("width", 0f)).floatValue();
        float height = ((Number) itemMap.getOrDefault("height", 0f)).floatValue();
        return new DetectionInfo(label, confidence, x, y, width, height);
    }
}
