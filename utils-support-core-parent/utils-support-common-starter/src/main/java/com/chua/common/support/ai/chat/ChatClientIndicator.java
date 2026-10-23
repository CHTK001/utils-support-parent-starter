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
}
