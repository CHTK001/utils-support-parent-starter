// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.safetensors;

import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SoMark OXR-1.0 版面解析翻译器（Markdown 输出）。
 *
 * <p>OXR 是 SoMark 开源的端到端文档解析模型（1.3B，Markdown + 布局坐标），
 * Python 服务通过 {@code document_ocr} 类型调用其远程 HTTP API 并返回结构化 Markdown。
 * 默认使用官方免费 API，可用系统属性 {@code oxr.base_url} / {@code oxr.api_key}
 * 或环境变量 {@code OXR_BASE_URL} / {@code OXR_API_KEY} 覆盖。</p>
 *
 * <p>对应业务接口：{@link com.chua.deeplearning.support.layout.LayoutDetector#parse(byte[])}。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Slf4j
public class OxrLayoutTranslator implements ITranslator<byte[], String> {

    /**
     * 翻译器名称（对应 Python 服务注册模型标识）。
     */
    private static final String MODEL_NAME = "oxr-layout";

    /**
     * Python 服务模型类型。
     */
    private static final String MODEL_TYPE = "document_ocr";

    /**
     * Python 服务客户端。
     */
    private final SafeTensorServiceClient client;

    /**
     * 无参构造：使用默认本地服务地址 {@code localhost:8765}。
     */
    public OxrLayoutTranslator() {
        this("localhost", 8765);
    }

    /**
     * 构造并指定 Python 服务地址。
     *
     * @param host Python 服务主机
     * @param port Python 服务端口
     */
    public OxrLayoutTranslator(String host, int port) {
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
                    buildParams()
            );
            Object output = result == null ? null : result.get("output");
            if (output instanceof Map<?, ?> map) {
                Object markdown = map.get("markdown");
                return markdown == null ? "" : markdown.toString();
            }
            return output == null ? "" : output.toString();
        } catch (Exception e) {
            log.error("[OXR] 版面解析失败: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * 构建 Python 服务调用参数（远程 OXR 地址与密钥，未配置时由服务端使用免费 API）。
     *
     * @return 参数 map
     */
    private static Map<String, Object> buildParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "base_url", System.getProperty("oxr.base_url", System.getenv("OXR_BASE_URL")));
        putIfPresent(params, "api_key", System.getProperty("oxr.api_key", System.getenv("OXR_API_KEY")));
        putIfPresent(params, "timeout", System.getProperty("oxr.timeout"));
        return params;
    }

    /**
     * 非空时写入参数。
     *
     * @param params 参数 map
     * @param key    键
     * @param value  值
     */
    private static void putIfPresent(Map<String, Object> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }
}