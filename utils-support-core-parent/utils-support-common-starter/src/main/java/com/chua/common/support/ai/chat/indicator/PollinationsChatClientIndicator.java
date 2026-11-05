// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pollinations 官方模型目录的 {@link ChatClientIndicator} 实现。
 *
 * <p>数据来自 {@code https://gen.pollinations.ai/models?reliability=all}（无需 Key），
 * 响应是模型对象数组。与其它目录相比，Pollinations 额外提供：</p>
 * <ul>
 *   <li>{@code supported_endpoints}：该模型支持的接入端点（Chat Completions /
 *       Responses / Messages / 文本端点）；</li>
 *   <li>{@code supported_parameters}：该模型支持的请求参数；</li>
 *   <li>{@code pricing.promptTextTokens} / {@code promptCachedTokens} /
 *       {@code promptCacheWriteTokens} / {@code completionTextTokens}（按 Token 报价）；</li>
 *   <li>{@code capabilities}、{@code input_modalities}、{@code context_length}、
 *       {@code max_completion_tokens} 与健康度。</li>
 * </ul>
 *
 * <p>价格口径与其它实现一致：USD / 百万 Token。Pollinations 的计价单位是
 * {@code pollen}，按其与美元的 1:1 口径归一（若后续官方调整兑换比例，只需改本类）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class PollinationsChatClientIndicator implements ChatClientIndicator {

    /**
     * Pollinations 公开模型目录（无需 Key）
     */
    public static final String DEFAULT_URL = "https://gen.pollinations.ai/models?reliability=all";

    /**
     * 来源标识
     */
    public static final String SOURCE = "pollinations";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 抓取缓存有效期：30 分钟
     */
    private static final long CACHE_TTL_MILLIS = 30L * 60L * 1000L;

    private final String url;
    private final ChatClientSetting setting;
    private final ObjectMapper objectMapper;

    private volatile Snapshot snapshot;

    public PollinationsChatClientIndicator(ChatClientSetting setting) {
        this(setting, new ObjectMapper());
    }

    public PollinationsChatClientIndicator(ChatClientSetting setting, ObjectMapper objectMapper) {
        String metricsUrl = setting != null ? setting.getMetricsUrl() : null;
        this.url = StringUtils.isNotBlank(metricsUrl) ? metricsUrl : DEFAULT_URL;
        this.setting = setting;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public PollinationsChatClientIndicator(String url) {
        this(url, new ObjectMapper());
    }

    public PollinationsChatClientIndicator(String url, ObjectMapper objectMapper) {
        this.url = StringUtils.isNotBlank(url) ? url : DEFAULT_URL;
        this.setting = null;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public String sourceUrl() {
        return url;
    }

    @Override
    public Long capturedAt() {
        Snapshot current = snapshot;
        return current == null ? null : current.capturedAt;
    }

    @Override
    public List<Map<String, Object>> supportedEndpoints() {
        Snapshot current = ensureSnapshot();
        return current == null ? List.of() : current.supportedEndpoints;
    }

    @Override
    public List<Map<String, Object>> supportedParameters() {
        Snapshot current = ensureSnapshot();
        return current == null ? List.of() : current.supportedParameters;
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

    /**
     * ensure Snapshot
     * @return 返回值
    */
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

    /**
     * 获取
     * @return 返回值
    */
    private Snapshot fetch() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
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
     * 解析
     * @param body 字符串
     * @return 返回值
    */
    private Snapshot parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode models = root.isArray() ? root : root.get("models");
            if (models == null || !models.isArray()) {
                return null;
            }
            Snapshot result = new Snapshot();
            result.capturedAt = System.currentTimeMillis();
            Map<String, Map<String, Object>> metricsByModel = new LinkedHashMap<>(models.size() * 2);
            Set<String> parameters = new LinkedHashSet<>(64);
            Set<String> endpoints = new LinkedHashSet<>(8);
            for (JsonNode item : models) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String fullId = IndicatorJsonSupport.text(item, "name");
                if (fullId == null) {
                    continue;
                }
                Map<String, Object> metrics = parseModel(item);
                metricsByModel.put(fullId, metrics);
                int slash = fullId.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < fullId.length()) {
                    metricsByModel.putIfAbsent(fullId.substring(slash + 1), metrics);
                }
                collectNames(item.get("supported_parameters"), parameters);
                collectNames(item.get("supported_endpoints"), endpoints);
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
     * parse Model
     * @param item 参数
     * @return 返回值
    */
    private Map<String, Object> parseModel(JsonNode item) {
        Map<String, Object> metrics = new LinkedHashMap<>(24);
        JsonNode pricing = item.get("pricing");
        if (pricing != null && pricing.isObject()) {
            IndicatorJsonSupport.put(metrics, "price_input_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("promptTextTokens")));
            IndicatorJsonSupport.put(metrics, "price_output_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("completionTextTokens")));
            IndicatorJsonSupport.put(metrics, "price_cache_hit_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("promptCachedTokens")));
            IndicatorJsonSupport.put(metrics, "price_cache_write_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("promptCacheWriteTokens")));
            BigDecimal image = IndicatorJsonSupport.perMillion(pricing.get("promptImageTokens"));
            if (image != null && image.signum() > 0) {
                metrics.put("price_image_input_per_musd", image);
            }
            BigDecimal audio = IndicatorJsonSupport.perMillion(pricing.get("promptAudioTokens"));
            if (audio != null && audio.signum() > 0) {
                metrics.put("price_audio_input_per_musd", audio);
            }
            BigDecimal video = IndicatorJsonSupport.perMillion(pricing.get("promptVideoTokens"));
            if (video != null && video.signum() > 0) {
                metrics.put("price_video_input_per_musd", video);
            }
            IndicatorJsonSupport.put(metrics, "pricing_currency",
                    IndicatorJsonSupport.text(pricing, "currency"));
        }
        // 按次计费的调整项：检索查询等
        JsonNode adjustments = item.get("pricing_adjustments");
        if (adjustments != null && adjustments.isArray()) {
            for (JsonNode adjustment : adjustments) {
                if (adjustment == null || !adjustment.isObject()) {
                    continue;
                }
                String kind = IndicatorJsonSupport.text(adjustment, "kind");
                BigDecimal price = IndicatorJsonSupport.decimal(adjustment, "price");
                BigDecimal quantity = IndicatorJsonSupport.decimal(adjustment, "quantity");
                if (price == null) {
                    continue;
                }
                if (quantity != null && quantity.signum() > 0) {
                    price = price.divide(quantity, 8, java.math.RoundingMode.HALF_UP);
                }
                if (kind != null && kind.contains("search")) {
                    metrics.put("price_web_search_per_unit", price.stripTrailingZeros());
                    metrics.put("web_search", true);
                }
            }
        }

        IndicatorJsonSupport.put(metrics, "context_window_tokens",
                IndicatorJsonSupport.longValue(item, "context_length"));
        IndicatorJsonSupport.put(metrics, "max_output_tokens",
                IndicatorJsonSupport.longValue(item, "max_completion_tokens"));

        JsonNode inputs = item.get("input_modalities");
        if (IndicatorJsonSupport.containsText(inputs, "image")) {
            metrics.put("image_input", true);
        }
        if (IndicatorJsonSupport.containsText(inputs, "audio")) {
            metrics.put("audio_input", true);
        }
        if (IndicatorJsonSupport.containsText(inputs, "video")) {
            metrics.put("video_input", true);
        }
        List<String> outputs = IndicatorJsonSupport.stringList(item.get("output_modalities"));
        if (!outputs.isEmpty()) {
            metrics.put("output_modalities", outputs);
        }

        JsonNode capabilities = item.get("capabilities");
        if (IndicatorJsonSupport.containsText(capabilities, "tool_calling")) {
            metrics.put("function_calling", true);
        }
        if (IndicatorJsonSupport.containsText(capabilities, "reasoning")) {
            metrics.put("reasoning", true);
        }
        if (IndicatorJsonSupport.containsText(capabilities, "web_search")) {
            metrics.put("web_search", true);
        }
        if (item.path("tools").asBoolean(false)) {
            metrics.put("function_calling", true);
        }
        if (item.path("reasoning").asBoolean(false)) {
            metrics.put("reasoning", true);
        }
        if (item.path("supports_structured_output").asBoolean(false)) {
            metrics.put("json_mode", true);
            metrics.put("structured_output", true);
        }

        IndicatorJsonSupport.put(metrics, "model_name",
                IndicatorJsonSupport.text(item, "title"));
        IndicatorJsonSupport.put(metrics, "model_provider",
                IndicatorJsonSupport.text(item, "publisher"));
        IndicatorJsonSupport.put(metrics, "category",
                IndicatorJsonSupport.text(item, "category"));
        if (item.path("paid_only").isBoolean()) {
            metrics.put("paid_only", item.path("paid_only").asBoolean());
        }
        IndicatorJsonSupport.put(metrics, "added_date",
                IndicatorJsonSupport.longValue(item, "added_date"));
        JsonNode health = item.get("health");
        IndicatorJsonSupport.put(metrics, "health_status",
                IndicatorJsonSupport.text(health, "status"));
        IndicatorJsonSupport.put(metrics, "health_success_rate",
                IndicatorJsonSupport.decimal(health, "success_rate"));
        JsonNode supported = item.get("supported_parameters");
        if (supported != null && supported.isArray()) {
            metrics.put("supported_parameters", IndicatorJsonSupport.toJava(supported));
        }
        JsonNode supportedEndpoints = item.get("supported_endpoints");
        if (supportedEndpoints != null && supportedEndpoints.isArray()) {
            metrics.put("supported_endpoints", IndicatorJsonSupport.toJava(supportedEndpoints));
        }
        return metrics;
    }

    /**
     * 收集数组中的字符串名称。
     * @param array 参数
     * @param target 参数
     * @return 返回值
    */
    private static void collectNames(JsonNode array, Set<String> target) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            String name = IndicatorJsonSupport.text(item);
            if (name != null) {
                target.add(name);
            }
        }
    }

    /**
     * 名称集合转 {@code [{name}]} 列表。
     * @param names 参数
     * @return 返回值
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
     * @param endpoints 参数
     * @return 返回值
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

    /**
     * read Price
     * @param key 字符串
     * @param modelId 字符串
     * @param snapshot 参数
     * @return 返回值
    */
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

    /**
     * read Long
     * @param key 字符串
     * @param modelId 字符串
     * @param snapshot 参数
     * @return 返回值
    */
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

    private static final class Snapshot {
        private Long capturedAt;
        private Map<String, Map<String, Object>> modelMetrics = Map.of();
        private List<Map<String, Object>> supportedParameters = List.of();
        private List<Map<String, Object>> supportedEndpoints = List.of();
    }
}