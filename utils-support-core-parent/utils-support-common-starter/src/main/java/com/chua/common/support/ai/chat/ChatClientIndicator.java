package com.chua.common.support.ai.chat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * AI 对话客户端指标能力接口。
 *
 * <p>与 {@link ChatClient} 同级的「指标」扩展接口：各服务商在本地模型目录（{@code GET /models}）
 * 之外，往往还能通过一个「模型指标 / 价格」端点提供多维信息，例如 OpenRouter、Pollinations 的
 * {@code /models} 返回项里携带的 {@code supported_endpoints}（支持的接入端点/协议）与
 * {@code supported_parameters}（支持的请求参数）。参考
 * <a href="https://gen.pollinations.ai/models?reliability=all">Pollinations 模型清单</a> 的形态。</p>
 *
 * <p>本接口是<b>能力探测</b>入口：{@code ChatClient} 通过
 * {@link ChatClient#indicator() indicator()} 返回当前服务商的指标实现（不支持时为 {@code null}）。
 * 各 provider 可按需实现本接口，把自家的价格、端点、参数等指标暴露出来；不实现或返回 null
 * 表示该服务商没有可用的指标数据，调用方（如 {@code pages/ai} 的模型卡片）应回退到
 * 后端的「模型指标库」快照，而不是把缺失当成 0。</p>
 *
 * <p>所有取值方法默认返回 {@code null} 或空集合，表示「未知 / 未提供」，
 * 实现类只需覆写自己真正能提供的部分。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ChatClientIndicator {

    /**
     * 指标数据的来源标识（服务商名 / 端点名），用于日志与前端标注数据出处。
     *
     * <p>该值会写入指标库的 {@code sys_ai_model_metrics_source} 列（长度有限），
     * 因此实现类应返回<b>短标识</b>（如 {@code openrouter}、{@code models.dev}），
     * 完整端点地址请通过 {@link #sourceUrl()} 提供。</p>
     *
     * @return 来源标识，未知时返回 null
     */
    default String source() {
        return null;
    }

    /**
     * 指标数据的完整来源地址（官方端点 / 定价页 URL），用于前端展示与溯源。
     *
     * @return 来源地址，未知时返回 null
     * @see #source()
     */
    default String sourceUrl() {
        return null;
    }

    /**
     * 当前指标快照的采集时间戳（毫秒）。
     *
     * @return 采集时间，未知时返回 null
     */
    default Long capturedAt() {
        return null;
    }

    /**
     * 支持的接入端点 / 协议列表（对应 pollinations 的 {@code supported_endpoints}）。
     *
     * <p>每个端点是一个 {@code Map}，通常含 {@code name}（如 openai / anthropic / gemini）、
     * {@code path}、{@code method} 等键。没有信息时返回空列表。</p>
     *
     * @return 支持的端点列表
     */
    default List<Map<String, Object>> supportedEndpoints() {
        return List.of();
    }

    /**
     * 支持的请求参数列表（对应 pollinations 的 {@code supported_parameters}）。
     *
     * <p>每个参数是一个 {@code Map}，通常含 {@code name}、{@code type}、{@code description}、
     * {@code default} 等键。没有信息时返回空列表。</p>
     *
     * @return 支持的参数列表
     */
    default List<Map<String, Object>> supportedParameters() {
        return List.of();
    }

    /**
     * 模型指标映射（模型 ID → 该模型的指标）。
     *
     * <p>内层 Map 的键与后端「模型指标库」快照保持一致（snake_case），
     * 例如 {@code price_input_per_musd}、{@code price_output_per_musd}、
     * {@code context_window_tokens}、{@code intelligence_index}、{@code output_speed_tps}。
     * 没有信息时返回空 Map。</p>
     *
     * @return 模型 ID 到指标映射
     */
    default Map<String, Map<String, Object>> modelMetrics() {
        return Map.of();
    }

    /**
     * 取单个模型的输入单价（货币单位 / 百万 Token）。
     *
     * @param modelId 模型 ID
     * @return 单价，未知时返回 null
     * @see #modelMetrics()
     */
    default BigDecimal inputUnitPrice(String modelId) {
        return null;
    }

    /**
     * 取单个模型的输出单价（货币单位 / 百万 Token）。
     *
     * @param modelId 模型 ID
     * @return 单价，未知时返回 null
     * @see #modelMetrics()
     */
    default BigDecimal outputUnitPrice(String modelId) {
        return null;
    }

    /**
     * 取单个模型的上下文窗口（Token 数）。
     *
     * @param modelId 模型 ID
     * @return 上下文窗口，未知时返回 null
     * @see #modelMetrics()
     */
    default Long contextWindowTokens(String modelId) {
        return null;
    }

    /**
     * 取单个模型的指标快照（未知时返回空 Map，不返回 null）。
     *
     * <p>用于需要按标准键读取任意指标（价格、评分、模态、思考档位）的调用方，
     * 避免为每个键都在本接口上加一个方法。</p>
     *
     * @param modelId 模型 ID
     * @return 指标 Map（snake_case 标准键）
     */
    default Map<String, Object> modelMetricsOf(String modelId) {
        if (modelId == null) {
            return Map.of();
        }
        Map<String, Map<String, Object>> all = modelMetrics();
        if (all == null || all.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> hit = all.get(modelId);
        if (hit != null) {
            return hit;
        }
        // 兼容 provider/model 与短 slug 两种键
        int slash = modelId.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < modelId.length()) {
            hit = all.get(modelId.substring(slash + 1));
            if (hit != null) {
                return hit;
            }
        }
        return Map.of();
    }

    /**
     * 单次请求的最大输出 Token 数（{@code max_output_tokens}）。
     *
     * @param modelId 模型 ID
     * @return 输出上限，未知时返回 null
     */
    default Long outputTokens(String modelId) {
        return longMetric(modelId, "max_output_tokens");
    }

    /**
     * 是否支持推理 / 思考模式（{@code reasoning}）。
     *
     * @param modelId 模型 ID
     * @return 支持返回 true，未知返回 null
     */
    default Boolean supportsReasoning(String modelId) {
        return boolMetric(modelId, "reasoning");
    }

    /**
     * 是否支持工具调用（{@code function_calling}）。
     *
     * @param modelId 模型 ID
     * @return 支持返回 true，未知返回 null
     */
    default Boolean supportsToolCall(String modelId) {
        return boolMetric(modelId, "function_calling");
    }

    /**
     * 是否支持图片输入（{@code image_input}）。
     *
     * @param modelId 模型 ID
     * @return 支持返回 true，未知返回 null
     */
    default Boolean supportsImageInput(String modelId) {
        return boolMetric(modelId, "image_input");
    }

    /**
     * 是否支持文件 / 附件输入（{@code attachment}）。
     *
     * @param modelId 模型 ID
     * @return 支持返回 true，未知返回 null
     */
    default Boolean supportsAttachment(String modelId) {
        return boolMetric(modelId, "attachment");
    }

    /**
     * 模型族（{@code model_family}），如 {@code claude-4}、{@code gpt-4o}。
     *
     * @param modelId 模型 ID
     * @return 模型族，未知时返回 null
     */
    default String modelFamily(String modelId) {
        return textMetric(modelId, "model_family");
    }

    /**
     * 知识截止日期（{@code knowledge_cutoff}）。
     *
     * @param modelId 模型 ID
     * @return 日期文本，未知时返回 null
     */
    default String knowledgeCutoff(String modelId) {
        return textMetric(modelId, "knowledge_cutoff");
    }

    /**
     * 输入模态列表（{@code input_modalities}），如 text / image / audio。
     *
     * @param modelId 模型 ID
     * @return 模态列表，未知时返回空列表
     */
    default List<String> inputModalities(String modelId) {
        return stringListMetric(modelId, "input_modalities");
    }

    /**
     * 输出模态列表（{@code output_modalities}），如 text / image。
     *
     * @param modelId 模型 ID
     * @return 模态列表，未知时返回空列表
     */
    default List<String> outputModalities(String modelId) {
        return stringListMetric(modelId, "output_modalities");
    }

    /**
     * 思考强度档位（{@code reasoning_efforts}），如 low / medium / high / max。
     *
     * @param modelId 模型 ID
     * @return 档位列表，未知时返回空列表
     */
    default List<String> reasoningEfforts(String modelId) {
        return stringListMetric(modelId, "reasoning_efforts");
    }

    /**
     * 读取整型指标。
     *
     * @param modelId 模型 ID
     * @param key     指标键
     * @return 值；缺失或非数值时返回 null
     */
    private Long longMetric(String modelId, String key) {
        Object value = modelMetricsOf(modelId).get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 读取布尔指标。
     *
     * @param modelId 模型 ID
     * @param key     指标键
     * @return 值；缺失时返回 null
     */
    private Boolean boolMetric(String modelId, String key) {
        Object value = modelMetricsOf(modelId).get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return null;
    }

    /**
     * 读取文本指标。
     *
     * @param modelId 模型 ID
     * @param key     指标键
     * @return 值；缺失或空白时返回 null
     */
    private String textMetric(String modelId, String key) {
        Object value = modelMetricsOf(modelId).get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    /**
     * 读取字符串列表指标（元素为对象时取 {@code name} / {@code type}）。
     *
     * @param modelId 模型 ID
     * @param key     指标键
     * @return 列表；缺失时返回空列表
     */
    private List<String> stringListMetric(String modelId, String key) {
        Object value = modelMetricsOf(modelId).get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String text) {
                if (!text.isBlank()) {
                    values.add(text);
                }
            } else if (item instanceof Map<?, ?> map) {
                Object name = map.get("name") != null ? map.get("name") : map.get("type");
                if (name != null) {
                    values.add(String.valueOf(name));
                }
            }
        }
        return values;
    }
}
