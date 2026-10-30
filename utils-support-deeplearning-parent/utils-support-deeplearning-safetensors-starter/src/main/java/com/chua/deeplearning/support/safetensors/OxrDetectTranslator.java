package com.chua.deeplearning.support.safetensors;

import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SoMark OXR-1.0 版面检测翻译器（检测框输出）。
 *
 * <p>调用与 {@link OxrLayoutTranslator} 相同的 Python 服务 / OXR 远程 API，但把返回的
 * 逐页 JSON 内容块（{@code pages[].blocks[]}，含 {@code type} 与 {@code bbox=[x1,y1,x2,y2]}）
 * 归一为「区域类型 → {@link PredictRectangle} 列表」，供
 * {@link com.chua.deeplearning.support.layout.LayoutDetector#detect(byte[])} 使用。</p>
 *
 * <p>默认使用官方免费 API，可用系统属性 {@code oxr.base_url} / {@code oxr.api_key}
 * 或环境变量 {@code OXR_BASE_URL} / {@code OXR_API_KEY} 覆盖。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Slf4j
public class OxrDetectTranslator implements ITranslator<byte[], Object> {

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
    public OxrDetectTranslator() {
        this("localhost", 8765);
    }

    /**
     * 构造并指定 Python 服务地址。
     *
     * @param host Python 服务主机
     * @param port Python 服务端口
     */
    public OxrDetectTranslator(String host, int port) {
        this.client = new SafeTensorServiceClient(host, port);
    }

    @Override
    public String name() {
        return "oxr-layout-detect";
    }

    @Override
    public Object translate(byte[] input) {
        Map<String, List<PredictRectangle>> grouped = new LinkedHashMap<>();
        if (input == null || input.length == 0) {
            return grouped;
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
            if (!(output instanceof Map<?, ?> outputMap)) {
                return grouped;
            }
            Object json = outputMap.get("json");
            if (!(json instanceof Map<?, ?> jsonMap)) {
                return grouped;
            }
            Object pagesObj = jsonMap.get("pages");
            if (!(pagesObj instanceof List<?> pages)) {
                return grouped;
            }
            for (Object pageObj : pages) {
                if (!(pageObj instanceof Map<?, ?> page)) {
                    continue;
                }
                Object blocksObj = page.get("blocks");
                if (!(blocksObj instanceof List<?> blocks)) {
                    continue;
                }
                for (Object blockObj : blocks) {
                    if (!(blockObj instanceof Map<?, ?> block)) {
                        continue;
                    }
                    String type = String.valueOf(block.getOrDefault("type", "unknown"));
                    PredictRectangle rect = toRectangle(block.get("bbox"), type);
                    if (rect != null) {
                        grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(rect);
                    }
                }
            }
            return grouped;
        } catch (Exception e) {
            log.error("[OXR] 版面检测失败: {}", e.getMessage(), e);
            return grouped;
        }
    }

    /**
     * 把 OXR 的 {@code bbox=[x1,y1,x2,y2]} 转为 {@link PredictRectangle}。
     *
     * @param bboxObj bbox 原始对象（List）
     * @param type    区域类型
     * @return 预测区域；bbox 非法时返回 null
     */
    private static PredictRectangle toRectangle(Object bboxObj, String type) {
        if (!(bboxObj instanceof List<?> bbox) || bbox.size() < 4) {
            return null;
        }
        float x1 = toFloat(bbox.get(0));
        float y1 = toFloat(bbox.get(1));
        float x2 = toFloat(bbox.get(2));
        float y2 = toFloat(bbox.get(3));
        float width = Math.max(0f, x2 - x1);
        float height = Math.max(0f, y2 - y1);
        return new PredictRectangle(x1, y1, width, height, 1.0f, 0, type);
    }

    /**
     * 数值转换。
     *
     * @param value 原始值
     * @return 浮点值
     */
    private static float toFloat(Object value) {
        return value instanceof Number number ? number.floatValue() : Float.parseFloat(String.valueOf(value));
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
