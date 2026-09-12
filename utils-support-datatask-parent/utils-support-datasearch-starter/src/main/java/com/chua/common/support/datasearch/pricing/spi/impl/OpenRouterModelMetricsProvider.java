package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractModelMetricsProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
* 打开router 统一模型指标提供者。
*
* <p>补充 Artificial Analysis 缺失的维度，数据来自官方 JSON API
* {@code https://openrouter.ai/api/v1/models}（公开、无需 key）：</p>
* <ul>
*   <li>价格：输入/输出（USD / 百万 Token）、缓存读/写、图片（USD / 张）、网络检索（USD / 次）</li>
*   <li>能力：图片输入（input_modalities）、网络检索（web_search 定价）、上下文窗口</li>
*   <li>智能：benchmarks.artificial_analysis.intelligence_index（部分模型）</li>
* </ul>
*
* <p>价格口径统一为 USD / 百万 Token（与 Artificial Analysis 一致），图片与检索按次计费。
* 模型 标识 取 {@code provider/model} 的尾段，与 Artificial Analysis 的 slug 对齐，便于
* {@code DataSearchModelPricingProvider} 多源合并。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("openrouter")
public class OpenRouterModelMetricsProvider extends AbstractModelMetricsProvider {

    /** 打开router 模型列表 API(免 键) */
    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/models";

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器

    /**
    * 从 打开router 拉取全部模型指标。
    *
    * @return 模型指标列表；接口不可达或结构变化时返回空列表
     */
    @Override
    public List<ModelDefinition> fetchOnlinePricing() {
        String json = fetchUrl(OPENROUTER_URL);
        if (json == null || json.isEmpty()) {
            return readClasspathPricing();
        }
        try {
            List<ModelDefinition> parsed = parseOpenRouter(json);
            return parsed.isEmpty() ? readClasspathPricing() : parsed;
        } catch (Exception e) {
            log.warn("[openrouter] 解析失败: {}", e.getMessage());
            return readClasspathPricing();
        }
    }

    /**
    * 解析 打开router 模型列表 JSON。
    *
    * @param json API 响应
    * @return 模型指标列表
    * @throws Exception JSON 解析失败时抛出
     */
    private List<ModelDefinition> parseOpenRouter(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return new ArrayList<>();
        }
        List<ModelDefinition> result = new ArrayList<>();
        for (JsonNode item : data) {
            String fullId = item.path("id").asText(null);
            if (fullId == null || fullId.isEmpty()) {
                continue;
            }
            String slug = fullId.contains("/") ? fullId.substring(fullId.lastIndexOf('/') + 1) : fullId;
            JsonNode pricing = item.path("pricing");
            JsonNode architecture = item.path("architecture");
            JsonNode benchmarks = item.path("benchmarks").path("artificial_analysis");

            BigDecimal prompt = perMillion(pricing.path("prompt"));
            BigDecimal completion = perMillion(pricing.path("completion"));
            BigDecimal cacheRead = perMillion(pricing.path("input_cache_read"));
            BigDecimal cacheWrite = perMillion(pricing.path("input_cache_write"));
            BigDecimal image = perImage(pricing.path("image"));
            BigDecimal webSearch = perImage(pricing.path("web_search"));
            BigDecimal internalReasoning = perMillion(pricing.path("internal_reasoning"));

            boolean imageInput = containsModality(architecture, "image");
            boolean webSearchSupport = webSearch != null;
            List<String> outputModalities = modalities(architecture.path("output_modalities"));

            long contextLength = item.path("context_length").asLong(0L);
            BigDecimal intelligence = number(benchmarks.path("intelligence_index"));

            ModelDefinition md = ModelDefinition.builder()
                    .id(slug)
                    .name(item.path("name").asText(slug))
                    .provider(fullId.substring(0, fullId.lastIndexOf('/')))
                    .description("OpenRouter")
                    .inputUnitPrice(prompt)
                    .outputUnitPrice(completion)
                    .cacheHitPrice(cacheRead)
                    .cacheWritePrice(cacheWrite)
                    .imagePrice(image)
                    .webSearchPrice(webSearch)
                    .internalReasoningPrice(internalReasoning)
                    .outputModalities(outputModalities)
                    .imageInput(imageInput ? Boolean.TRUE : null)
                    .webSearch(webSearchSupport ? Boolean.TRUE : null)
                    .contextWindowTokens(contextLength > 0 ? contextLength : null)
                    .intelligenceIndex(intelligence)
                    .currency("USD")
                    .build();
            result.add(md);
        }
        return result;
    }

    /**
    * 将每 令牌 价格换算为 USD / 百万 令牌。
    *
    * @param node 价格节点
    * @return 每百万 令牌 价格；缺失/非数值/零时返回 空
     */
    private BigDecimal perMillion(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        if (text == null || text.isEmpty() || "0".equals(text)) {
            return null;
        }
        try {
            return new BigDecimal(text).multiply(BigDecimal.valueOf(1_000_000L)).stripTrailingZeros();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
    * 读取按次计费的价格（图片/检索）。
    *
    * @param node 价格节点
    * @return 单次价格；缺失/非数值/零时返回 空
     */
    private BigDecimal perImage(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        if (text == null || text.isEmpty() || "0".equals(text)) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
    * 读取模态数组为字符串列表。
    *
    * @param node 模态数组节点
    * @return 模态列表；非数组时返回空列表
     */
    private List<String> modalities(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode m : node) {
            result.add(m.asText());
        }
        return result;
    }

    /**
    * 判断输入模态是否包含指定类型。
    *
    * @param architecture 架构节点
    * @param modality     模态名（如 镜像）
    * @return 包含返回 true
     */
    private boolean containsModality(JsonNode architecture, String modality) {
        JsonNode mods = architecture.path("input_modalities");
        if (!mods.isArray()) {
            return false;
        }
        for (JsonNode m : mods) {
            if (modality.equals(m.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
    * 读取数值节点。
    *
    * @param node 数值节点
    * @return 数值；缺失/非数值返回 空
     */
    private BigDecimal number(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
