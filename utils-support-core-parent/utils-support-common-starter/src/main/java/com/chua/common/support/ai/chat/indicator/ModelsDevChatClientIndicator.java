package com.chua.common.support.ai.chat.indicator;

import com.chua.common.support.ai.chat.ChatClientIndicator;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Models.dev 公开聚合端点的 {@link ChatClientIndicator} 实现。
 *
 * <p>Models.dev（{@code https://models.dev/api.json}，无需 Key）聚合了数百家主流
 * LLM 提供商的模型清单与指标，是 OpenAI、Anthropic、Google、DeepSeek、通义千问、
 * 智谱、Moonshot、MiniMax 等<b>没有公开定价 API</b> 的厂商最稳定的机器可读来源。
 * 返回结构为：</p>
 *
 * <pre>{@code
 * {
 *   "openai": {
 *     "id": "openai",
 *     "name": "OpenAI",
 *     "models": {
 *       "gpt-4o": {
 *         "id": "gpt-4o",
 *         "cost": { "input": 2.5, "output": 10, "cache_read": 1.25 },
 *         "limit": { "context": 128000, "output": 16384 },
 *         "modalities": { "input": ["text", "image"], "output": ["text"] },
 *         "reasoning": false,
 *         "tool_call": true,
 *         "structured_output": true,
 *         "attachment": true,
 *         "open_weights": false
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>价格口径：{@code cost.input/output/cache_read/cache_write/reasoning}
 * 已是 <b>USD / 百万 Token</b>，直接映射，不做换算；{@code limit.context}
 * 直接映射上下文窗口，{@code limit.output} 映射输出上限。</p>
 *
 * <p>指定 {@code provider} 时只保留该厂商的模型；不指定时返回全量（用于
 * {@code models.dev} 作为独立服务商的场景）。抓取结果带 30 分钟 TTL，避免
 * 长生命周期的 {@code ChatClient} 永远拿到旧价格。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ModelsDevChatClientIndicator implements ChatClientIndicator {

    /**
     * Models.dev 公开聚合端点（无需 Key）
     */
    public static final String DEFAULT_URL = "https://models.dev/api.json";

    /**
     * 来源标识
     */
    public static final String SOURCE = "models.dev";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);

    /**
     * 抓取缓存有效期：30 分钟
     */
    private static final long CACHE_TTL_MILLIS = 30L * 60L * 1000L;

    private final String provider;
    private final String sourceUrl;
    private final String metricsUrl;
    private final ChatClientSetting setting;
    private final ObjectMapper objectMapper;

    private volatile Snapshot snapshot;

    public ModelsDevChatClientIndicator(ChatClientSetting setting) {
        this(setting, new ObjectMapper());
    }

    public ModelsDevChatClientIndicator(ChatClientSetting setting, ObjectMapper objectMapper) {
        this.provider = setting != null ? setting.getProvider() : null;
        this.metricsUrl = setting != null ? setting.getMetricsUrl() : null;
        this.sourceUrl = StringUtils.isNotBlank(metricsUrl) ? metricsUrl : DEFAULT_URL;
        this.setting = setting;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public ModelsDevChatClientIndicator(String provider) {
        this(provider, null, new ObjectMapper());
    }

    public ModelsDevChatClientIndicator(String provider, ObjectMapper objectMapper) {
        this(provider, null, objectMapper);
    }

    /**
     * 指定厂商键与端点地址（{@code provider} 为 null 时返回全量）。
     *
     * @param provider      Models.dev 厂商键
     * @param metricsUrl    自定义端点（null 用默认地址）
     * @param objectMapper  JSON 映射器
     */
    public ModelsDevChatClientIndicator(String provider, String metricsUrl, ObjectMapper objectMapper) {
        this.provider = provider;
        this.metricsUrl = metricsUrl;
        this.sourceUrl = StringUtils.isNotBlank(metricsUrl) ? metricsUrl : DEFAULT_URL;
        this.setting = null;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public String sourceUrl() {
        return sourceUrl;
    }

    @Override
    public Long capturedAt() {
        Snapshot current = snapshot;
        return current == null ? null : current.capturedAt;
    }

    @Override
    public List<Map<String, Object>> supportedEndpoints() {
        return List.of();
    }

    @Override
    public List<Map<String, Object>> supportedParameters() {
        return List.of();
    }

    @Override
    public Map<String, Map<String, Object>> modelMetrics() {
        Snapshot current = ensureSnapshot();
        return current == null ? Map.of() : current.modelMetrics;
    }

    @Override
    public BigDecimal inputUnitPrice(String modelId) {
        return readPrice(ensureSnapshot(), modelId, "price_input_per_musd");
    }

    @Override
    public BigDecimal outputUnitPrice(String modelId) {
        return readPrice(ensureSnapshot(), modelId, "price_output_per_musd");
    }

    @Override
    public Long contextWindowTokens(String modelId) {
        return readLong(ensureSnapshot(), modelId, "context_window_tokens");
    }

    private Snapshot ensureSnapshot() {
        Snapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (current != null && now - current.capturedAt < CACHE_TTL_MILLIS) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            now = System.currentTimeMillis();
            if (current != null && now - current.capturedAt < CACHE_TTL_MILLIS) {
                return current;
            }
            Snapshot fetched = fetch();
            if (fetched != null) {
                snapshot = fetched;
                return fetched;
            }
            return current;
        }
    }

    private Snapshot fetch() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(sourceUrl))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET();
            if (setting != null && setting.getExtraHeaders() != null) {
                setting.getExtraHeaders().forEach(builder::header);
            }
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            return parse(response.body());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 解析 models.dev 的「厂商 → 模型」两级结构。
     *
     * @param body 响应体
     * @return 快照；解析失败返回 null
     */
    private Snapshot parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            Map<String, Map<String, Object>> metricsByModel = new LinkedHashMap<>(256);
            if (StringUtils.isNotBlank(provider)) {
                JsonNode providerNode = root.get(provider);
                if (providerNode != null && providerNode.isObject()) {
                    collectProvider(provider, providerNode, metricsByModel);
                }
            } else {
                Iterator<Map.Entry<String, JsonNode>> providers = root.fields();
                while (providers.hasNext()) {
                    Map.Entry<String, JsonNode> entry = providers.next();
                    if (entry.getValue() != null && entry.getValue().isObject()) {
                        collectProvider(entry.getKey(), entry.getValue(), metricsByModel);
                    }
                }
            }
            Snapshot result = new Snapshot();
            result.capturedAt = System.currentTimeMillis();
            result.modelMetrics = metricsByModel;
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 收集单个厂商下的全部模型。
     *
     * @param providerId 厂商键
     * @param providerNode 厂商节点
     * @param target 目标 Map
     */
    private void collectProvider(String providerId, JsonNode providerNode,
                                 Map<String, Map<String, Object>> target) {
        JsonNode models = providerNode.has("models") ? providerNode.get("models") : providerNode;
        if (models == null || !models.isObject()) {
            return;
        }
        String providerName = IndicatorJsonSupport.text(providerNode, "name");
        Iterator<Map.Entry<String, JsonNode>> iter = models.fields();
        while (iter.hasNext()) {
            Map.Entry<String, JsonNode> entry = iter.next();
            JsonNode modelNode = entry.getValue();
            if (modelNode == null || !modelNode.isObject()) {
                continue;
            }
            Map<String, Object> metrics = modelMetricsOf(providerId, providerName, modelNode);
            if (!metrics.isEmpty()) {
                target.putIfAbsent(entry.getKey(), metrics);
            }
        }
    }

    /**
     * 汇总单个模型的指标 Map（models.dev 字段 → 标准 snake_case 键）。
     */
    private Map<String, Object> modelMetricsOf(String providerId, String providerName, JsonNode model) {
        Map<String, Object> metrics = new LinkedHashMap<>(24);

        JsonNode cost = model.get("cost");
        if (cost != null && cost.isObject()) {
            IndicatorJsonSupport.put(metrics, "price_input_per_musd",
                    nonNegative(IndicatorJsonSupport.decimal(cost, "input")));
            IndicatorJsonSupport.put(metrics, "price_output_per_musd",
                    nonNegative(IndicatorJsonSupport.decimal(cost, "output")));
            IndicatorJsonSupport.put(metrics, "price_cache_hit_per_musd",
                    nonNegative(IndicatorJsonSupport.decimal(cost, "cache_read")));
            IndicatorJsonSupport.put(metrics, "price_cache_write_per_musd",
                    nonNegative(IndicatorJsonSupport.decimal(cost, "cache_write")));
            IndicatorJsonSupport.put(metrics, "price_internal_reasoning_per_musd",
                    nonNegative(IndicatorJsonSupport.decimal(cost, "reasoning")));
        }

        JsonNode limit = model.get("limit");
        if (limit != null && limit.isObject()) {
            Long context = IndicatorJsonSupport.longValue(limit, "context");
            if (context != null && context > 0) {
                metrics.put("context_window_tokens", context);
            }
            Long output = IndicatorJsonSupport.longValue(limit, "output");
            if (output != null && output > 0) {
                metrics.put("max_output_tokens", output);
            }
        }

        putBoolean(metrics, "reasoning", model, "reasoning");
        putBoolean(metrics, "function_calling", model, "tool_call");
        putBoolean(metrics, "structured_output", model, "structured_output");
        putBoolean(metrics, "attachment", model, "attachment");
        putBoolean(metrics, "open_weights", model, "open_weights");
        putBoolean(metrics, "temperature", model, "temperature");
        if (metrics.containsKey("structured_output")) {
            metrics.put("json_mode", Boolean.TRUE.equals(metrics.get("structured_output")));
        }

        // 思考档位：reasoning_options → 思考档位列表（effort 类型）或调参类型（budget_tokens/toggle）
        JsonNode reasoningOptions = model.get("reasoning_options");
        if (reasoningOptions != null && reasoningOptions.isArray()) {
            List<String> efforts = null;
            List<String> types = null;
            for (JsonNode option : reasoningOptions) {
                if (option == null || !option.isObject()) {
                    continue;
                }
                String type = IndicatorJsonSupport.text(option, "type");
                if (type == null || type.isBlank()) {
                    continue;
                }
                if (types == null) {
                    types = new java.util.ArrayList<>(2);
                }
                types.add(type);
                if ("effort".equals(type)) {
                    List<String> values = IndicatorJsonSupport.stringList(option.get("values"));
                    if (!values.isEmpty()) {
                        if (efforts == null) {
                            efforts = new java.util.ArrayList<>(values.size());
                        }
                        efforts.addAll(values);
                    }
                }
            }
            if (efforts != null && !efforts.isEmpty()) {
                metrics.put("reasoning_efforts", dedupe(efforts));
            }
            if (types != null && !types.isEmpty()) {
                metrics.put("reasoning_option_types", dedupe(types));
            }
        }

        JsonNode modalities = model.get("modalities");
        if (modalities != null && modalities.isObject()) {
            JsonNode inputs = modalities.get("input");
            if (IndicatorJsonSupport.containsText(inputs, "image")) {
                metrics.put("image_input", true);
            }
            if (IndicatorJsonSupport.containsText(inputs, "audio")) {
                metrics.put("audio_input", true);
            }
            if (IndicatorJsonSupport.containsText(inputs, "video")) {
                metrics.put("video_input", true);
            }
            if (IndicatorJsonSupport.containsText(inputs, "pdf")) {
                metrics.put("pdf_input", true);
            }
            List<String> outputs = IndicatorJsonSupport.stringList(modalities.get("output"));
            if (!outputs.isEmpty()) {
                metrics.put("output_modalities", outputs);
            }
        }

        IndicatorJsonSupport.put(metrics, "model_name", IndicatorJsonSupport.text(model, "name"));
        IndicatorJsonSupport.put(metrics, "model_family", IndicatorJsonSupport.text(model, "family"));
        IndicatorJsonSupport.put(metrics, "knowledge_cutoff",
                IndicatorJsonSupport.text(model, "knowledge"));
        IndicatorJsonSupport.put(metrics, "release_date",
                IndicatorJsonSupport.text(model, "release_date"));
        IndicatorJsonSupport.put(metrics, "last_updated",
                IndicatorJsonSupport.text(model, "last_updated"));
        if (StringUtils.isNotBlank(providerId)) {
            metrics.put("model_provider", providerId);
        }
        if (StringUtils.isNotBlank(providerName)) {
            metrics.put("model_provider_name", providerName);
        }
        return metrics;
    }

    /**
     * 负值（表示价格不定）按未知处理。
     */
    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? null : value;
    }

    /**
     * 列表去重（保持首次出现顺序）。
     *
     * @param values 原始列表
     * @return 去重后的新列表
     */
    private static List<String> dedupe(List<String> values) {
        List<String> result = new java.util.ArrayList<>(values.size());
        for (String value : values) {
            if (value != null && !value.isBlank() && !result.contains(value)) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private static void putBoolean(Map<String, Object> target, String key, JsonNode model, String field) {
        JsonNode value = model.get(field);
        if (value != null && value.isBoolean()) {
            target.put(key, value.asBoolean());
        }
    }

    private static BigDecimal readPrice(Snapshot snapshot, String modelId, String key) {
        if (snapshot == null || modelId == null) {
            return null;
        }
        Map<String, Object> metrics = snapshot.modelMetrics.get(modelId);
        if (metrics == null) {
            return null;
        }
        Object value = metrics.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    private static Long readLong(Snapshot snapshot, String modelId, String key) {
        if (snapshot == null || modelId == null) {
            return null;
        }
        Map<String, Object> metrics = snapshot.modelMetrics.get(modelId);
        if (metrics == null) {
            return null;
        }
        Object value = metrics.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 一次抓取的结构化快照。
     */
    private static final class Snapshot {
        private Long capturedAt;
        private Map<String, Map<String, Object>> modelMetrics = Map.of();
    }
}
