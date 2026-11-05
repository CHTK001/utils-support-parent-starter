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
 * OpenRouter 官方模型目录的 {@link ChatClientIndicator} 实现。
 *
 * <p>数据来自 OpenRouter 公开 API {@code https://openrouter.ai/api/v1/models}
 * （无需 Key），响应结构为 {@code { "data": [ {model}, ... ] }}。本实现解析：</p>
 * <ul>
 *   <li>价格：{@code pricing.prompt}/{@code completion}（USD / Token）换算为
 *       USD / 百万 Token；缓存读 / 写、内部推理同口径；图片与联网检索按次保留；</li>
 *   <li>上下文：{@code context_length}、{@code top_provider.max_completion_tokens}；</li>
 *   <li>能力：{@code architecture.input_modalities}、{@code supported_parameters}
 *       （reasoning / tools / response_format / web_search）；</li>
 *   <li>智能指数：{@code benchmarks.artificial_analysis.intelligence_index}。</li>
 * </ul>
 *
 * <p>模型键同时保留 OpenRouter 的 {@code provider/model} 全路径与尾段 slug，
 * 便于上层按两种写法命中；入库时按归一化键去重。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OpenRouterChatClientIndicator implements ChatClientIndicator {

    /**
     * OpenRouter 公开模型目录（无需 Key）
     */
    public static final String DEFAULT_URL = "https://openrouter.ai/api/v1/models";

    /**
     * 抓取超时
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 抓取缓存有效期：30 分钟（价格变更后最多半小时生效）
     */
    private static final long CACHE_TTL_MILLIS = 30L * 60L * 1000L;

    /**
     * 来源标识（写入指标库 source 列，长度受列宽限制）
     */
    public static final String SOURCE = "openrouter";

    private final String url;
    private final ChatClientSetting setting;
    private final ObjectMapper objectMapper;

    /**
     * 最近一次抓取快照
     */
    private volatile Snapshot snapshot;

    public OpenRouterChatClientIndicator(ChatClientSetting setting) {
        this(setting, new ObjectMapper());
    }

    public OpenRouterChatClientIndicator(ChatClientSetting setting, ObjectMapper objectMapper) {
        String metricsUrl = setting != null ? setting.getMetricsUrl() : null;
        this.url = StringUtils.isNotBlank(metricsUrl) ? metricsUrl : DEFAULT_URL;
        this.setting = setting;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public OpenRouterChatClientIndicator(String url) {
        this(url, new ObjectMapper());
    }

    public OpenRouterChatClientIndicator(String url, ObjectMapper objectMapper) {
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
        return List.of();
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
     * 取带 TTL 的快照；过期后重新抓取。
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
     * 抓取并解析；失败时返回 null，不抛出。
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
     * 解析 OpenRouter 模型目录。
     *
     * @param body 响应体
     * @return 快照；解析失败返回 null
     */
    private Snapshot parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.isArray() ? root : root.get("data");
            if (data == null || !data.isArray()) {
                return null;
            }
            Snapshot result = new Snapshot();
            result.capturedAt = System.currentTimeMillis();
            Map<String, Map<String, Object>> metricsByModel = new LinkedHashMap<>(data.size() * 2);
            Set<String> parameters = new LinkedHashSet<>(64);
            for (JsonNode item : data) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String fullId = IndicatorJsonSupport.text(item, "id");
                if (fullId == null) {
                    continue;
                }
                Map<String, Object> metrics = parseModel(item);
                metricsByModel.put(fullId, metrics);
                int slash = fullId.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < fullId.length()) {
                    metricsByModel.putIfAbsent(fullId.substring(slash + 1), metrics);
                }
                JsonNode supported = item.get("supported_parameters");
                if (supported != null && supported.isArray()) {
                    for (JsonNode parameter : supported) {
                        String name = IndicatorJsonSupport.text(parameter);
                        if (name != null) {
                            parameters.add(name);
                        }
                    }
                }
            }
            result.modelMetrics = metricsByModel;
            List<Map<String, Object>> parameterList = new ArrayList<>(parameters.size());
            for (String name : parameters) {
                Map<String, Object> row = new LinkedHashMap<>(2);
                row.put("name", name);
                parameterList.add(row);
            }
            result.supportedParameters = List.copyOf(parameterList);
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 解析单个模型节点。
     * @param item 参数
     * @return 返回值
    */
    private Map<String, Object> parseModel(JsonNode item) {
        Map<String, Object> metrics = new LinkedHashMap<>(24);
        JsonNode pricing = item.get("pricing");
        if (pricing != null && pricing.isObject()) {
            IndicatorJsonSupport.put(metrics, "price_input_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("prompt")));
            IndicatorJsonSupport.put(metrics, "price_output_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("completion")));
            IndicatorJsonSupport.put(metrics, "price_cache_hit_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("input_cache_read")));
            IndicatorJsonSupport.put(metrics, "price_cache_write_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("input_cache_write")));
            IndicatorJsonSupport.put(metrics, "price_internal_reasoning_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("internal_reasoning")));
            BigDecimal imagePrice = IndicatorJsonSupport.perUnit(pricing.get("image"));
            if (imagePrice != null && imagePrice.signum() > 0) {
                metrics.put("price_image_per_unit", imagePrice);
            }
            BigDecimal searchPrice = IndicatorJsonSupport.perUnit(pricing.get("web_search"));
            if (searchPrice != null && searchPrice.signum() > 0) {
                metrics.put("price_web_search_per_unit", searchPrice);
            }
        }
        IndicatorJsonSupport.put(metrics, "context_window_tokens",
                IndicatorJsonSupport.longValue(item, "context_length"));
        JsonNode topProvider = item.get("top_provider");
        IndicatorJsonSupport.put(metrics, "max_output_tokens",
                IndicatorJsonSupport.longValue(topProvider, "max_completion_tokens"));

        JsonNode architecture = item.get("architecture");
        if (architecture != null && architecture.isObject()) {
            JsonNode inputs = architecture.get("input_modalities");
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
            String modality = IndicatorJsonSupport.text(architecture, "modality");
            IndicatorJsonSupport.put(metrics, "modality", modality);
            List<String> outputs = IndicatorJsonSupport.stringList(architecture.get("output_modalities"));
            if (!outputs.isEmpty()) {
                metrics.put("output_modalities", outputs);
            }
        }

        JsonNode supported = item.get("supported_parameters");
        boolean reasoning = item.has("reasoning")
                || IndicatorJsonSupport.containsText(supported, "reasoning")
                || IndicatorJsonSupport.containsText(supported, "include_reasoning");
        boolean functionCalling = IndicatorJsonSupport.containsText(supported, "tools")
                || IndicatorJsonSupport.containsText(supported, "tool_choice");
        boolean structuredOutput = IndicatorJsonSupport.containsText(supported, "response_format")
                || IndicatorJsonSupport.containsText(supported, "structured_outputs");
        if (reasoning) {
            metrics.put("reasoning", true);
        }
        if (functionCalling) {
            metrics.put("function_calling", true);
        }
        if (structuredOutput) {
            metrics.put("json_mode", true);
            metrics.put("structured_output", true);
        }
        if (metrics.containsKey("price_web_search_per_unit")) {
            metrics.put("web_search", true);
        }
        if (supported != null && supported.isArray()) {
            metrics.put("supported_parameters", IndicatorJsonSupport.toJava(supported));
        }

        JsonNode benchmarks = item.path("benchmarks").path("artificial_analysis");
        IndicatorJsonSupport.put(metrics, "intelligence_index",
                IndicatorJsonSupport.decimal(benchmarks, "intelligence_index"));
        IndicatorJsonSupport.put(metrics, "coding_index",
                IndicatorJsonSupport.decimal(benchmarks, "coding_index"));

        IndicatorJsonSupport.put(metrics, "model_name", IndicatorJsonSupport.text(item, "name"));
        String fullId = IndicatorJsonSupport.text(item, "id");
        if (fullId != null) {
            int slash = fullId.lastIndexOf('/');
            if (slash > 0) {
                metrics.put("model_provider", fullId.substring(0, slash));
            }
        }
        IndicatorJsonSupport.put(metrics, "hugging_face_id",
                IndicatorJsonSupport.text(item, "hugging_face_id"));
        IndicatorJsonSupport.put(metrics, "knowledge_cutoff",
                IndicatorJsonSupport.text(item, "knowledge_cutoff"));
        IndicatorJsonSupport.put(metrics, "created", IndicatorJsonSupport.longValue(item, "created"));
        return metrics;
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

    /**
     * 一次抓取的结构化快照。
     */
    private static final class Snapshot {
        private Long capturedAt;
        private Map<String, Map<String, Object>> modelMetrics = Map.of();
        private List<Map<String, Object>> supportedParameters = List.of();
    }
}