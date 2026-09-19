package com.chua.deeplearning.support.safetensors;

import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * safetensor 人脸检测翻译器，输入图像字节数组，输出 {@link PredictRectangle} 列表。
 * <p>
 * 默认连接 {@code facade-face} 模型与 {@code face_detection} 任务类型，可通过构造函数覆盖。
 * 推理失败时返回空列表，不抛出异常。
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class SafeTensorTranslator implements ITranslator<byte[], List<PredictRectangle>> {

    /**
     * HTTP 客户端
     */
    private final SafeTensorServiceClient client;

    /**
     * 模型名称
     */
    private final String modelName;

    /**
     * 模型类型（默认 face_detection）
     */
    private final String modelType;

    /**
     * 使用默认 facade-face + face_detection 构造。
     *
     * @param host safetensor服务 主机
     * @param port safetensor服务 端口
     */
    public SafeTensorTranslator(String host, int port) {
        this(host, port, "facade-face", "face_detection");
    }

    /**
     * @param host      safetensor服务 主机
     * @param port      safetensor服务 端口
     * @param modelName 模型名称
     * @param modelType 模型类型
     */
    public SafeTensorTranslator(String host, int port, String modelName, String modelType) {
        this.client = new SafeTensorServiceClient(host, port);
        this.modelName = modelName;
        this.modelType = modelType;
    }

    @Override
    /** 名称 */
    public String name() {
        return modelName;
    }

    /**
    * 把图像字节发给 safetensor服务 并解析返回人脸框列表。
    *
    * @param input 图像字节数组
    * @return PredictRectangle 列表
    */
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
