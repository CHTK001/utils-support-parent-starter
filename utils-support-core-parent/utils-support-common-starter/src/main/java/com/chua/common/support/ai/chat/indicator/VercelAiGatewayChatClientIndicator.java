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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Vercel AI Gateway 公开模型目录的 {@link ChatClientIndicator} 实现。
 *
 * <p>数据来自 {@code https://ai-gateway.vercel.sh/v1/models}（公开、无需 Key），
 * 响应结构为 {@code { "data": [ {model}, ... ] }}，每个模型带
 * {@code pricing.input/output}（USD / Token）、{@code context_window}、
 * {@code max_tokens}、{@code modalities}、{@code supported_parameters} 与
 * {@code tags}。</p>
 *
 * <p>本实现主要用于 models.dev 未收录的厂商（如腾讯混元）：构造时传入
 * {@code owner}（{@code tencent}）后只保留该厂商的模型。也支持不传 owner
 * 的全量模式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class VercelAiGatewayChatClientIndicator implements ChatClientIndicator {

    /**
     * Vercel AI Gateway 公开模型目录
     */
    public static final String DEFAULT_URL = "https://ai-gateway.vercel.sh/v1/models";

    /**
     * 来源标识
     */
    public static final String SOURCE = "vercel";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);

    /**
     * 抓取缓存有效期：30 分钟
     */
    private static final long CACHE_TTL_MILLIS = 30L * 60L * 1000L;

    private final String url;
    private final String owner;
    private final ChatClientSetting setting;
    private final ObjectMapper objectMapper;

    private volatile Snapshot snapshot;

    public VercelAiGatewayChatClientIndicator(ChatClientSetting setting, String owner) {
        this(setting, owner, new ObjectMapper());
    }

    public VercelAiGatewayChatClientIndicator(ChatClientSetting setting, String owner,
                                               ObjectMapper objectMapper) {
        String metricsUrl = setting != null ? setting.getMetricsUrl() : null;
        this.url = StringUtils.isNotBlank(metricsUrl) ? metricsUrl : DEFAULT_URL;
        this.owner = StringUtils.isBlank(owner) ? null : owner.trim().toLowerCase(Locale.ROOT);
        this.setting = setting;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public VercelAiGatewayChatClientIndicator(String owner) {
        this(null, owner, new ObjectMapper());
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
                if (owner != null && !fullId.toLowerCase(Locale.ROOT).startsWith(owner + "/")) {
                    continue;
                }
                Map<String, Object> metrics = parseModel(item);
                metricsByModel.put(fullId, metrics);
                int slash = fullId.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < fullId.length()) {
                    String slug = fullId.substring(slash + 1);
                    // 同 slug 可能来自不同 owner：仅在未被占用时注册短键
                    metricsByModel.putIfAbsent(slug, metrics);
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

    private Map<String, Object> parseModel(JsonNode item) {
        Map<String, Object> metrics = new LinkedHashMap<>(24);
        JsonNode pricing = item.get("pricing");
        if (pricing != null && pricing.isObject()) {
            IndicatorJsonSupport.put(metrics, "price_input_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("input")));
            IndicatorJsonSupport.put(metrics, "price_output_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("output")));
            IndicatorJsonSupport.put(metrics, "price_cache_hit_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("input_cache_read")));
            IndicatorJsonSupport.put(metrics, "price_cache_write_per_musd",
                    IndicatorJsonSupport.perMillion(pricing.get("input_cache_write")));
        }
        IndicatorJsonSupport.put(metrics, "context_window_tokens",
                IndicatorJsonSupport.longValue(item, "context_window"));
        IndicatorJsonSupport.put(metrics, "max_output_tokens",
                IndicatorJsonSupport.longValue(item, "max_tokens"));

        JsonNode modalities = item.get("modalities");
        JsonNode inputs = modalities == null ? null : modalities.get("input");
        if (IndicatorJsonSupport.containsText(inputs, "image")) {
            metrics.put("image_input", true);
        }
        if (IndicatorJsonSupport.containsText(inputs, "audio")) {
            metrics.put("audio_input", true);
        }
        if (IndicatorJsonSupport.containsText(inputs, "video")) {
            metrics.put("video_input", true);
        }
        if (modalities != null && modalities.isObject()) {
            List<String> outputs = IndicatorJsonSupport.stringList(modalities.get("output"));
            if (!outputs.isEmpty()) {
                metrics.put("output_modalities", outputs);
            }
        }

        JsonNode supported = item.get("supported_parameters");
        boolean reasoning = IndicatorJsonSupport.containsText(supported, "reasoning")
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

        JsonNode tags = item.get("tags");
        List<String> tagList = IndicatorJsonSupport.stringList(tags);
        if (!tagList.isEmpty()) {
            metrics.put("tags", tagList);
            for (String tag : tagList) {
                if ("tool-use".equals(tag)) {
                    metrics.put("function_calling", true);
                } else if ("reasoning".equals(tag)) {
                    metrics.put("reasoning", true);
                } else if ("structured-output".equals(tag)) {
                    metrics.put("json_mode", true);
                    metrics.put("structured_output", true);
                } else if ("open-weights".equals(tag)) {
                    metrics.put("open_weights", true);
                }
            }
        }
        if (supported != null && supported.isArray()) {
            metrics.put("supported_parameters", IndicatorJsonSupport.toJava(supported));
        }
        IndicatorJsonSupport.put(metrics, "model_name", IndicatorJsonSupport.text(item, "name"));
        IndicatorJsonSupport.put(metrics, "model_type", IndicatorJsonSupport.text(item, "type"));
        IndicatorJsonSupport.put(metrics, "owned_by", IndicatorJsonSupport.text(item, "owned_by"));
        IndicatorJsonSupport.put(metrics, "knowledge_cutoff",
                IndicatorJsonSupport.text(item, "knowledge"));
        IndicatorJsonSupport.put(metrics, "released", IndicatorJsonSupport.longValue(item, "released"));
        IndicatorJsonSupport.put(metrics, "created", IndicatorJsonSupport.longValue(item, "created"));
        return metrics;
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

    private static final class Snapshot {
        private Long capturedAt;
        private Map<String, Map<String, Object>> modelMetrics = Map.of();
        private List<Map<String, Object>> supportedParameters = List.of();
    }
}
