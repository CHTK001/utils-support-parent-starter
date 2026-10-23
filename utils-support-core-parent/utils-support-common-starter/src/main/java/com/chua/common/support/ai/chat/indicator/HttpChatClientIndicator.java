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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于配置 URL 的 {@link ChatClientIndicator} 通用实现。
 *
 * <p>当服务商在 {@link ChatClientSetting#getMetricsUrl()} 中配置了「模型指标 / 价格」
 * 端点（如公司内部的模型目录、兼容 OpenAI {@code /models} 的网关、Pollinations
 * 的 {@code /models?reliability=all}），本实现会抓取该端点并尽量兼容常见的
 * JSON 结构：</p>
 *
 * <ul>
 *   <li>模型数组：顶层数组、{@code {models:[...]}}、{@code {data:[...]}}、
 *       {@code {data:{models:[...]}}}；</li>
 *   <li>价格：{@code pricing.input/output}（每 Token 或每百万 Token，按数值量级
 *       自动判定）、{@code pricing.prompt/completion}、{@code pricing.promptTextTokens/
 *       completionTextTokens}、{@code cost.input/output}（models.dev 口径）；</li>
 *   <li>上下文与输出上限：{@code context_window}、{@code context_length}、
 *       {@code limit.context}、{@code max_completion_tokens}、{@code limit.output}；</li>
 *   <li>能力：{@code supported_parameters}、{@code capabilities}、
 *       {@code architecture.input_modalities}、{@code modalities.input} 与常见布尔字段；</li>
 *   <li>端点与参数：顶层或模型级的 {@code supported_endpoints} / {@code supported_parameters}。</li>
 * </ul>
 *
 * <p>未配置 URL 或抓取 / 解析失败时，各取值保持 {@code null} 或空集合，
 * 上层（如 {@code pages/ai}）应据此回退到后端「模型指标库」快照，而不是把缺失当成 0。
 * 抓取结果缓存 10 分钟，兼顾价格时效与展示性能。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HttpChatClientIndicator implements ChatClientIndicator {

    /**
     * 来源标识
     */
    public static final String SOURCE = "http";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 抓取缓存有效期：10 分钟
     */
    private static final long CACHE_TTL_MILLIS = 10L * 60L * 1000L;

    private final ChatClientSetting setting;
    private final String metricsUrl;
    private final ObjectMapper objectMapper;

    /**
     * 抓取缓存
     */
    private volatile IndicatorSnapshot snapshot;

    public HttpChatClientIndicator(ChatClientSetting setting) {
        this(setting, new ObjectMapper());
    }

    public HttpChatClientIndicator(ChatClientSetting setting, ObjectMapper objectMapper) {
        if (setting == null) {
            throw new IllegalArgumentException("setting 不能为 null");
        }
        this.setting = setting;
        this.metricsUrl = setting.getMetricsUrl();
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * 当前使用的指标 URL；未配置时返回 {@code null}。
     *
     * @return URL
     */
    public String metricsUrl() {
        return metricsUrl;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public String sourceUrl() {
        return metricsUrl;
    }

    @Override
    public Long capturedAt() {
        IndicatorSnapshot current = snapshot;
        return current == null ? null : current.capturedAt;
    }

    @Override
    public List<Map<String, Object>> supportedEndpoints() {
        IndicatorSnapshot current = ensureSnapshot();
        return current == null ? List.of() : current.supportedEndpoints;
    }

    @Override
    public List<Map<String, Object>> supportedParameters() {
        IndicatorSnapshot current = ensureSnapshot();
        return current == null ? List.of() : current.supportedParameters;
    }

    @Override
    public Map<String, Map<String, Object>> modelMetrics() {
        IndicatorSnapshot current = ensureSnapshot();
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

    /**
     * 拉取并解析指标 URL；失败时返回 {@code null}（上层回退指标库快照）。
     */
    private IndicatorSnapshot ensureSnapshot() {
        IndicatorSnapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (current != null && now - current.capturedAt < CACHE_TTL_MILLIS) {
            return current;
        }
        if (StringUtils.isBlank(metricsUrl)) {
            return snapshot;
        }
        synchronized (this) {
            current = snapshot;
            now = System.currentTimeMillis();
            if (current != null && now - current.capturedAt < CACHE_TTL_MILLIS) {
                return current;
            }
            IndicatorSnapshot fetched = fetch();
            if (fetched != null) {
                snapshot = fetched;
                return fetched;
            }
            return current;
        }
    }

    /**
     * 执行一次抓取与解析；任何异常都返回 {@code null}，不抛出。
     */
    private IndicatorSnapshot fetch() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(metricsUrl))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET();
            Map<String, String> headers = setting.getExtraHeaders();
            if (headers != null) {
                headers.forEach(builder::header);
            }
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
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
     * 解析通用模型目录 JSON。
     *
     * @param body 响应体
     * @return 解析结果；解析失败返回 {@code null}
     */
    private IndicatorSnapshot parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null) {
                return null;
            }
            JsonNode models = locateModels(root);
            if (models == null) {
                return null;
            }
            IndicatorSnapshot result = new IndicatorSnapshot();
            result.capturedAt = System.currentTimeMillis();
            Map<String, Map<String, Object>> metricsByModel = new LinkedHashMap<>(64);
            Set<String> parameters = new LinkedHashSet<>(64);
            Set<String> endpoints = new LinkedHashSet<>(8);
            if (root.isObject()) {
                collectNames(root.get("supported_parameters"), parameters);
                collectNames(root.get("supported_endpoints"), endpoints);
            }
            Iterable<JsonNode> nodes = models.isArray() ? models : List.of(models);
            for (JsonNode model : nodes) {
                if (model == null || !model.isObject()) {
                    continue;
                }
                String modelId = modelIdOf(model);
                if (modelId == null) {
                    continue;
                }
                Map<String, Object> metrics = metricsOf(model);
                metricsByModel.put(modelId, metrics);
                int slash = modelId.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < modelId.length()) {
                    metricsByModel.putIfAbsent(modelId.substring(slash + 1), metrics);
                }
                collectNames(model.get("supported_parameters"), parameters);
                collectNames(model.get("supported_endpoints"), endpoints);
                JsonNode modelEndpoints = model.get("supported_endpoints");
                if (modelEndpoints != null && modelEndpoints.isArray()) {
                    metrics.put("supported_endpoints", IndicatorJsonSupport.toJava(modelEndpoints));
                }
                JsonNode modelParameters = model.get("supported_parameters");
                if (modelParameters != null && modelParameters.isArray()) {
                    metrics.put("supported_parameters", IndicatorJsonSupport.toJava(modelParameters));
                }
            }
            result.modelMetrics = metricsByModel;
            result.supportedParameters = toNameList(parameters);
            result.supportedEndpoints = toEndpointList(endpoints);
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 在多种常见包装结构中找到模型数组 / 单模型对象。
     */
    private JsonNode locateModels(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        if (!root.isObject()) {
            return null;
        }
        JsonNode models = root.get("models");
        if (models != null && models.isArray()) {
            return models;
        }
        JsonNode data = root.get("data");
        if (data != null) {
            if (data.isArray()) {
                return data;
            }
            if (data.isObject()) {
                JsonNode nested = data.get("models");
                if (nested != null && nested.isArray()) {
                    return nested;
                }
                if (data.has("id") || data.has("name") || data.has("model")) {
                    return data;
                }
            }
        }
        if (models != null && models.isObject() && models.has("models")) {
            JsonNode nested = models.get("models");
            if (nested != null && nested.isArray()) {
                return nested;
            }
        }
        if (root.has("id") || root.has("name") || root.has("model")) {
            return root;
        }
        return null;
    }

    /**
     * 提取模型标识：{@code id} 优先，其次 {@code name}、{@code model}。
     */
    private static String modelIdOf(JsonNode model) {
        String id = IndicatorJsonSupport.text(model, "id");
        if (id != null) {
            return id;
        }
        id = IndicatorJsonSupport.text(model, "name");
        if (id != null) {
            return id;
        }
        return IndicatorJsonSupport.text(model, "model");
    }

    /**
     * 汇总单个模型的指标 Map。
     */
    private Map<String, Object> metricsOf(JsonNode model) {
        Map<String, Object> metrics = new LinkedHashMap<>(32);
        // 1) 目录已给出标准指标（metrics 子对象）时优先采用
        JsonNode explicitMetrics = model.get("metrics");
        if (explicitMetrics != null && explicitMetrics.isObject()) {
            copyObject(explicitMetrics, metrics);
        }
        // 2) 价格：兼容 pricing / cost 两类结构
        JsonNode pricing = model.get("pricing");
        if (pricing != null && pricing.isObject()) {
            putHeuristicPrice(metrics, "price_input_per_musd", pricing.get("input"));
            putHeuristicPrice(metrics, "price_output_per_musd", pricing.get("output"));
            putHeuristicPrice(metrics, "price_cache_hit_per_musd", pricing.get("cache_read"));
            putHeuristicPrice(metrics, "price_cache_write_per_musd", pricing.get("cache_write"));
            putHeuristicPrice(metrics, "price_input_per_musd", pricing.get("prompt"));
            putHeuristicPrice(metrics, "price_output_per_musd", pricing.get("completion"));
            putPerTokenPrice(metrics, "price_input_per_musd", pricing.get("promptTextTokens"));
            putPerTokenPrice(metrics, "price_output_per_musd", pricing.get("completionTextTokens"));
            putPerTokenPrice(metrics, "price_cache_hit_per_musd", pricing.get("promptCachedTokens"));
            putPerTokenPrice(metrics, "price_cache_write_per_musd", pricing.get("promptCacheWriteTokens"));
            putPerTokenPrice(metrics, "price_cache_hit_per_musd", pricing.get("input_cache_read"));
            putPerTokenPrice(metrics, "price_cache_write_per_musd", pricing.get("input_cache_write"));
            putUnitPrice(metrics, "price_image_per_unit", pricing.get("image"));
            putUnitPrice(metrics, "price_web_search_per_unit", pricing.get("web_search"));
            putPerTokenPrice(metrics, "price_internal_reasoning_per_musd",
                    pricing.get("internal_reasoning"));
        }
        JsonNode cost = model.get("cost");
        if (cost != null && cost.isObject()) {
            putMillionPrice(metrics, "price_input_per_musd", cost.get("input"));
            putMillionPrice(metrics, "price_output_per_musd", cost.get("output"));
            putMillionPrice(metrics, "price_cache_hit_per_musd", cost.get("cache_read"));
            putMillionPrice(metrics, "price_cache_write_per_musd", cost.get("cache_write"));
            putMillionPrice(metrics, "price_internal_reasoning_per_musd", cost.get("reasoning"));
        }

        // 3) 上下文与输出上限
        Long context = firstLong(model, "context_window", "context_length",
                "max_context_tokens", "max_input_tokens");
        JsonNode limit = model.get("limit");
        if (context == null) {
            context = IndicatorJsonSupport.longValue(limit, "context");
        }
        if (context != null && context > 0) {
            metrics.put("context_window_tokens", context);
        }
        Long maxOutput = firstLong(model, "max_output_tokens", "max_completion_tokens");
        if (maxOutput == null) {
            maxOutput = IndicatorJsonSupport.longValue(limit, "output");
        }
        if (maxOutput == null) {
            JsonNode topProvider = model.get("top_provider");
            maxOutput = IndicatorJsonSupport.longValue(topProvider, "max_completion_tokens");
        }
        if (maxOutput != null && maxOutput > 0) {
            metrics.put("max_output_tokens", maxOutput);
        }

        // 4) 能力标记：显式布尔优先，其次参数列表 / 模态
        copyBoolean(metrics, "reasoning", model, "reasoning");
        copyBoolean(metrics, "reasoning", model, "reasoning_model");
        copyBoolean(metrics, "function_calling", model, "function_calling");
        copyBoolean(metrics, "function_calling", model, "tool_call");
        copyBoolean(metrics, "function_calling", model, "tools");
        copyBoolean(metrics, "structured_output", model, "structured_output");
        copyBoolean(metrics, "json_mode", model, "json_mode");
        copyBoolean(metrics, "image_input", model, "image_input");
        copyBoolean(metrics, "attachment", model, "attachment");
        copyBoolean(metrics, "web_search", model, "web_search");
        copyBoolean(metrics, "open_weights", model, "open_weights");

        JsonNode architecture = model.get("architecture");
        JsonNode inputs = architecture == null ? null : architecture.get("input_modalities");
        if (inputs == null) {
            inputs = model.get("input_modalities");
        }
        JsonNode modalities = model.get("modalities");
        if (inputs == null && modalities != null) {
            inputs = modalities.get("input");
        }
        if (inputs != null) {
            if (IndicatorJsonSupport.containsText(inputs, "image")) {
                metrics.put("image_input", true);
            }
            if (IndicatorJsonSupport.containsText(inputs, "audio")) {
                metrics.put("audio_input", true);
            }
            if (IndicatorJsonSupport.containsText(inputs, "video")) {
                metrics.put("video_input", true);
            }
            if (IndicatorJsonSupport.containsText(inputs, "file")) {
                metrics.put("file_input", true);
            }
            metrics.put("input_modalities", IndicatorJsonSupport.toJava(inputs));
        }
        if (modalities != null && modalities.isObject()) {
            List<String> outputs = IndicatorJsonSupport.stringList(modalities.get("output"));
            if (!outputs.isEmpty()) {
                metrics.put("output_modalities", outputs);
            }
        }

        JsonNode supported = model.get("supported_parameters");
        if (IndicatorJsonSupport.containsText(supported, "reasoning")
                || IndicatorJsonSupport.containsText(supported, "include_reasoning")) {
            metrics.put("reasoning", true);
        }
        if (IndicatorJsonSupport.containsText(supported, "tools")
                || IndicatorJsonSupport.containsText(supported, "tool_choice")) {
            metrics.put("function_calling", true);
        }
        if (IndicatorJsonSupport.containsText(supported, "response_format")
                || IndicatorJsonSupport.containsText(supported, "structured_outputs")) {
            metrics.put("json_mode", true);
            metrics.put("structured_output", true);
        }
        JsonNode capabilities = model.get("capabilities");
        if (IndicatorJsonSupport.containsText(capabilities, "tool_calling")) {
            metrics.put("function_calling", true);
        }
        if (IndicatorJsonSupport.containsText(capabilities, "reasoning")) {
            metrics.put("reasoning", true);
        }
        if (IndicatorJsonSupport.containsText(capabilities, "web_search")) {
            metrics.put("web_search", true);
        }
        if (metrics.containsKey("price_web_search_per_unit")) {
            metrics.put("web_search", true);
        }

        // 5) 展示 / 溯源字段
        IndicatorJsonSupport.put(metrics, "model_name",
                firstText(model, "name", "title", "display_name"));
        IndicatorJsonSupport.put(metrics, "model_provider",
                firstText(model, "publisher", "owned_by", "provider"));
        IndicatorJsonSupport.put(metrics, "model_type",
                firstText(model, "type", "category", "model_type"));
        IndicatorJsonSupport.put(metrics, "knowledge_cutoff",
                firstText(model, "knowledge", "knowledge_cutoff"));
        IndicatorJsonSupport.put(metrics, "created", IndicatorJsonSupport.longValue(model, "created"));
        IndicatorJsonSupport.put(metrics, "released", IndicatorJsonSupport.longValue(model, "released"));

        JsonNode benchmarks = model.path("benchmarks").path("artificial_analysis");
        IndicatorJsonSupport.put(metrics, "intelligence_index",
                IndicatorJsonSupport.decimal(benchmarks, "intelligence_index"));
        IndicatorJsonSupport.put(metrics, "coding_index",
                IndicatorJsonSupport.decimal(benchmarks, "coding_index"));
        return metrics;
    }

    /**
     * 复制 metrics 子对象（已存在的标准键优先）。
     */
    private void copyObject(JsonNode object, Map<String, Object> target) {
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            target.putIfAbsent(field.getKey(), IndicatorJsonSupport.toJava(field.getValue()));
        }
    }

    /**
     * 每百万口径价格；已存在时不覆盖。
     */
    private static void putMillionPrice(Map<String, Object> target, String key, JsonNode node) {
        if (IndicatorJsonSupport.has(target, key)) {
            return;
        }
        IndicatorJsonSupport.put(target, key, IndicatorJsonSupport.decimal(node));
    }

    /**
     * 每 Token 口径价格；已存在时不覆盖。
     */
    private static void putPerTokenPrice(Map<String, Object> target, String key, JsonNode node) {
        if (IndicatorJsonSupport.has(target, key)) {
            return;
        }
        IndicatorJsonSupport.put(target, key, IndicatorJsonSupport.perMillion(node));
    }

    /**
     * 每 Token / 每百万自适应口径；已存在时不覆盖。
     */
    private static void putHeuristicPrice(Map<String, Object> target, String key, JsonNode node) {
        if (IndicatorJsonSupport.has(target, key)) {
            return;
        }
        IndicatorJsonSupport.put(target, key, IndicatorJsonSupport.perMillionHeuristic(node));
    }

    /**
     * 按次计费价格；已存在时不覆盖，0 表示不单独收费，不写入。
     */
    private static void putUnitPrice(Map<String, Object> target, String key, JsonNode node) {
        if (IndicatorJsonSupport.has(target, key)) {
            return;
        }
        BigDecimal value = IndicatorJsonSupport.perUnit(node);
        if (value != null && value.signum() > 0) {
            target.put(key, value);
        }
    }

    /**
     * 复制布尔字段：节点为布尔时才写入。
     */
    private static void copyBoolean(Map<String, Object> target, String key, JsonNode model, String field) {
        JsonNode value = model.get(field);
        if (value != null && value.isBoolean()) {
            target.put(key, value.asBoolean());
        }
    }

    /**
     * 取第一个存在的 Long 字段。
     */
    private static Long firstLong(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            Long value = IndicatorJsonSupport.longValue(node, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 取第一个存在的文本字段。
     */
    private static String firstText(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            String value = IndicatorJsonSupport.text(node, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 收集数组中的字符串名称。
     */
    private static void collectNames(JsonNode array, Set<String> target) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            String name = item != null && item.isObject()
                    ? firstText(item, "name", "path", "id")
                    : IndicatorJsonSupport.text(item);
            if (name != null) {
                target.add(name);
            }
        }
    }

    /**
     * 名称集合转 {@code [{name}]} 列表。
     */
    private static List<Map<String, Object>> toNameList(Set<String> names) {
        List<Map<String, Object>> rows = new ArrayList<>(names.size());
        for (String name : names) {
            Map<String, Object> row = new LinkedHashMap<>(2);
            row.put("name", name);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    /**
     * 端点集合转 {@code [{name, path}]} 列表。
     */
    private static List<Map<String, Object>> toEndpointList(Set<String> endpoints) {
        List<Map<String, Object>> rows = new ArrayList<>(endpoints.size());
        for (String name : endpoints) {
            Map<String, Object> row = new LinkedHashMap<>(2);
            row.put("name", name);
            row.put("path", name);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static BigDecimal readPrice(IndicatorSnapshot snapshot, String modelId, String key) {
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

    private static Long readLong(IndicatorSnapshot snapshot, String modelId, String key) {
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
     * 一次抓取 + 解析的结果快照。
     */
    private static final class IndicatorSnapshot {
        private Long capturedAt;
        private List<Map<String, Object>> supportedEndpoints = List.of();
        private List<Map<String, Object>> supportedParameters = List.of();
        private Map<String, Map<String, Object>> modelMetrics = Map.of();
    }
}
