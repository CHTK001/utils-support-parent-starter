package com.chua.deeplearning.support.safetensors;

import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.Map;

/**
 * Datalab Indra OCR 版面解析翻译器。
 *
 * <p>Indra 是 CPU 端到端 OCR/版面解析模型，Python 服务通过
 * {@code document_ocr} 类型接收图片并返回 Markdown（含阅读顺序与版面）。</p>
 *
 * <p>Java 侧只负责注册与 HTTP 桥接，不内嵌 Python 依赖。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class IndraLayoutTranslator implements ITranslator<byte[], String> {

    /**
     * 翻译器名称
     */
    private static final String MODEL_NAME = "indra-layout";

    /**
     * Python 服务模型类型
     */
    private static final String MODEL_TYPE = "document_ocr";

    /**
     * Python 服务客户端
     */
    private final SafeTensorServiceClient client;

    /**
     * 无参构造：使用默认本地服务地址 {@code localhost:8765}。
     */
    public IndraLayoutTranslator() {
        this("localhost", 8765);
    }

    /**
     * 构造并指定 Python 服务地址。
     *
     * @param host Python 服务主机
     * @param port Python 服务端口
     */
    public IndraLayoutTranslator(String host, int port) {
        this.client = new SafeTensorServiceClient(host, port);
    }

    @Override
    public String name() {
        return MODEL_NAME;
    }

    @Override
    public String translate(byte[] input) {
        if (input == null || input.length == 0) {
            return "";
        }
        try {
            String image = Base64.getEncoder().encodeToString(input);
            Map<String, Object> result = client.infer(
                    MODEL_NAME,
                    MODEL_TYPE,
                    Map.of("image", image),
                    Map.of("max_new_tokens", 8192)
            );
            Object output = result == null ? null : result.get("output");
            return output == null ? "" : output.toString();
        } catch (Exception e) {
            log.error("[Indra] 版面解析失败: {}", e.getMessage(), e);
            return "";
        }
    }
}