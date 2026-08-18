package com.chua.deeplearning.support.safetensors;

import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * SafeTensor 版面解析翻译器。
 * <p>
 * 调用 Python SafeTensor HTTP 推理服务，将文档图片转换为结构化 Markdown 文本。
 * 支持 Unlimited-OCR、OvisOCR2 等端到端文档解析模型。
 * 对应业务接口：{@link com.chua.deeplearning.support.layout.LayoutDetector#parse(byte[])}
 * </p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class SafeTensorLayoutTranslator implements ITranslator<byte[], String> {

    /** 客户端 */
    /** 客户端 */
    private final SafeTensorServiceClient client;
    /** 模型名称 */
    /** 模型名称 */
    private final String modelName;
    /** 模型类型 */
    /** 模型类型 */
    private final String modelType;

    public SafeTensorLayoutTranslator(String host, int port, String modelName, String modelType) {
        this.client = new SafeTensorServiceClient(host, port);
        this.modelName = modelName;
        this.modelType = modelType;
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String translate(byte[] input) {
        if (input == null || input.length == 0) {
            return "";
        }
        try {
            Map<String, Object> result = client.infer(
                    modelName,
                    modelType,
                    Map.of("image", input),
                    Map.of("max_new_tokens", 2048)
            );
            if (result == null) {
                return "";
            }
            Object output = result.get("output");
            return output != null ? output.toString() : "";
        } catch (Exception e) {
            log.error("SafeTensor 版面解析失败: {}", e.getMessage(), e);
            return "";
        }
    }
}
