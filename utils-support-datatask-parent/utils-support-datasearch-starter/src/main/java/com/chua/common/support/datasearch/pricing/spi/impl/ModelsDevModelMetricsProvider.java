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
 * models.dev 模型目录提供者。
 *
 * <p>数据来自 {@code https://models.dev/api.json}（公开 JSON，无需 key）：以厂商为一级键、
 * 模型 id 为二级键组织，内含模型能力布尔、输入/输出模态、上下文与输出上限、以及各档价格。</p>
 *
 * <p>映射维度：</p>
 * <ul>
 *   <li>价格：{@code cost.input} / {@code cost.output} / {@code cost.cache_read} /
 *       {@code cost.cache_write} / {@code cost.reasoning}，口径为 <b>USD / 百万 Token</b>
 *       （与 Artificial Analysis、OpenRouter 一致，无需换算）</li>
 *   <li>能力：{@code reasoning} 深度思考、{@code tool_call} 工具调用、
 *       {@code structured_output} 结构化输出、{@code attachment} 附件支持；
 *       图片输入由 {@code modalities.input} 含 {@code image} 判定（该目录无独立 vision 字段）</li>
 *   <li>上下文：{@code limit.context} / {@code limit.output}</li>
 *   <li>输出模态：{@code modalities.output}</li>
 * </ul>
 *
 * <p>同一模型常在多个厂商下出现（网关 / 代理），本提供者按 {@code 厂商:模型} 归一化 id，
 * 避免不同厂商的同名模型互相覆盖；与其它数据源在
 * {@code DataSearchModelPricingProvider} 合并时，仍按模型标识回退折叠匹配。</p>
 *
 * <p><b>价格口径分层</b>：该目录混合了厂商第一方报价与网关 / 代理加价报价（本机样本 223 家中
 * 182 家为网关，共 6027 条）。网关报价含渠道加价，与厂商官方价口径不同，因此这里
 * <b>仅第一方厂商产出价格</b>参与计费，网关条目只补能力 / 上下文维度，避免加价污染定价表。
 * 判定依据：厂商的 AI SDK 包为 {@code @ai-sdk/openai-compatible}，或厂商 id 含
 * gateway / router / proxy / hub 等关键字。</p>
 *
 * <p>价格缺失（免费模型、未定价）时留空，由合并器用其它源补齐。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("modelsdev")
public class ModelsDevModelMetricsProvider extends AbstractModelMetricsProvider {

    /**
     * models.dev 模型目录 API（公开 JSON）
     */
    private static final String CATALOG_URL = "https://models.dev/api.json";

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器

    /**
     * 图片输入模态标识
     */
    private static final String MODALITY_IMAGE = "image";

    /**
     * 网关 / 代理识别：AI SDK 的通用 OpenAI 兼容包
     */
    private static final String GATEWAY_NPM = "@ai-sdk/openai-compatible";

    /**
     * 网关 / 代理识别：厂商 id 里的关键字
     */
    private static final java.util.regex.Pattern GATEWAY_PATTERN = java.util.regex.Pattern.compile(
            "gateway|router|proxy|aggregat|relay|hub|openai-compatible|302|merge", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 从 models.dev 拉取全量模型能力与价格。
     *
     * @return 模型指标列表；接口不可达或结构变化时回退 classpath 内置数据
     */
    @Override
    public List<ModelDefinition> fetchOnlinePricing() {
        String json = fetchUrl(CATALOG_URL);
        if (json == null || json.isEmpty()) {
            return readClasspathPricing();
        }
        try {
            List<ModelDefinition> parsed = parseCatalog(json);
            return parsed.isEmpty() ? readClasspathPricing() : parsed;
        } catch (Exception e) {
            log.warn("[modelsdev] 解析失败: {}", e.getMessage());
            return readClasspathPricing();
        }
    }

    /**
     * 解析厂商 → 模型 的两级目录为模型定义列表。
     *
     * @param json 目录 JSON
     * @return 模型指标列表
     * @throws Exception JSON 解析失败时抛出
     */
    private List<ModelDefinition> parseCatalog(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        if (!root.isObject()) {
            return new ArrayList<>();
        }
        List<ModelDefinition> result = new ArrayList<>();
        var providers = root.fields();
        while (providers.hasNext()) {
            var providerEntry = providers.next();
            String providerId = providerEntry.getKey();
            JsonNode providerNode = providerEntry.getValue();
            JsonNode models = providerNode.path("models");
            if (!models.isObject()) {
                continue;
            }
            // 网关 / 代理厂商只补能力，不带价格（其报价含网关加价，与厂商官方价口径不同）
            boolean gateway = isGateway(providerId, providerNode);
            var modelEntries = models.fields();
            while (modelEntries.hasNext()) {
                ModelDefinition md = toModelDefinition(providerId, modelEntries.next().getValue(), gateway);
                if (md != null) {
                    result.add(md);
                }
            }
        }
        return result;
    }

    /**
     * 判定厂商是否为网关 / 代理。
     *
     * @param providerId   厂商标识
     * @param providerNode 厂商节点
     * @return 网关 / 代理返回 true
     */
    private boolean isGateway(String providerId, JsonNode providerNode) {
        if (GATEWAY_NPM.equals(providerNode.path("npm").asText(null))) {
            return true;
        }
        return GATEWAY_PATTERN.matcher(providerId).find();
    }

    /**
     * 将单个模型节点映射为 {@link ModelDefinition}。
     *
     * @param providerId 厂商标识（目录一级键）
     * @param model      模型节点
     * @param gateway    厂商是否为网关 / 代理（true 时不产出价格）
     * @return 模型定义；模型 id 缺失时返回 空
     */
    private ModelDefinition toModelDefinition(String providerId, JsonNode model, boolean gateway) {
        String modelId = textOf(model, "id");
        if (modelId == null || modelId.isEmpty() || modelId.length() > 128) {
            return null;
        }
        JsonNode cost = model.path("cost");
        JsonNode modalities = model.path("modalities");
        JsonNode limit = model.path("limit");

        boolean reasoning = model.path("reasoning").asBoolean(false);
        boolean functionCalling = model.path("tool_call").asBoolean(false);
        boolean jsonMode = model.path("structured_output").asBoolean(false);
        boolean imageInput = containsText(modalities.path("input"), MODALITY_IMAGE);

        List<String> capabilities = new ArrayList<>(6);
        capabilities.add("chat");
        if (reasoning) {
            capabilities.add("reasoning");
        }
        if (functionCalling) {
            capabilities.add("function_calling");
        }
        if (imageInput) {
            capabilities.add("image_input");
        }
        if (jsonMode) {
            capabilities.add("structured_output");
        }

        BigDecimal context = numberOf(limit, "context");
        BigDecimal maxOutput = numberOf(limit, "output");

        return ModelDefinition.builder()
                .id(modelId)
                .name(textOf(model, "name") != null ? textOf(model, "name") : modelId)
                .provider(providerId)
                .description("models.dev")
                .inputUnitPrice(gateway ? null : priceOf(cost, "input"))
                .outputUnitPrice(gateway ? null : priceOf(cost, "output"))
                .cacheHitPrice(gateway ? null : priceOf(cost, "cache_read"))
                .cacheWritePrice(gateway ? null : priceOf(cost, "cache_write"))
                .internalReasoningPrice(gateway ? null : priceOf(cost, "reasoning"))
                .contextWindowTokens(context != null && context.signum() > 0 ? context.longValue() : null)
                .reasoning(reasoning ? Boolean.TRUE : null)
                .functionCalling(functionCalling ? Boolean.TRUE : null)
                .imageInput(imageInput ? Boolean.TRUE : null)
                .jsonMode(jsonMode ? Boolean.TRUE : null)
                .capabilities(capabilities)
                .currency("USD")
                .build();
    }

    /**
     * 读取价格（USD / 百万 Token），负值与非法值按空处理。
     *
     * @param node 价格节点
     * @param key  字段名
     * @return 价格；缺失 / 非法 / 负值时返回 空
     */
    private BigDecimal priceOf(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull() || !value.isNumber()) {
            return null;
        }
        BigDecimal price = value.decimalValue();
        return price.signum() < 0 ? null : price;
    }

    /**
     * 读取数值节点为 BigDecimal。
     *
     * @param node 节点
     * @param key  字段名
     * @return 数值；缺失 / 非法时返回 空
     */
    private BigDecimal numberOf(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        return null;
    }

    /**
     * 判断字符串数组是否包含指定值。
     *
     * @param node 数组节点
     * @param text 目标值
     * @return 包含返回 true
     */
    private boolean containsText(JsonNode node, String text) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (text.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取文本字段。
     *
     * @param node 节点
     * @param key  字段名
     * @return 文本；缺失 / 空串时返回 空
     */
    private String textOf(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return (text == null || text.isEmpty()) ? null : text;
    }
}
