package com.chua.common.support.ai.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
* AI 模型定义。
* <p>
* 描述一个 AI 模型的基本信息，包括 ID、名称、提供商、描述和能力列表。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelDefinition {

    /**
    * 模型 ID
     */
    private String id;

    /**
    * 模型名称
     */
    private String name;

    /**
    * 模型提供商
     */
    private String provider;

    /**
    * 模型描述
     */
    private String description;

    /**
    * 模型能力列表
     */
    private List<String> capabilities;

    /**
    * 模型文件远程下载地址（本地找不到时自动下载）
     */
    private String downloadUrl;

    /**
    * 下载文件是否为压缩包（zip）
     */
    private boolean compress;

    /**
    * 压缩包内目标文件名（compress=true 时生效）
     */
    private String downloadFileName;

    /**
    * 输入单价（每 Token）
     */
    private BigDecimal inputUnitPrice;

    /**
    * 输出单价（每 Token）
     */
    private BigDecimal outputUnitPrice;

    /**
    * 货币单位
     */
    @Builder.Default
    /** Currency */
    private String currency = "USD";

    /**
    * 智能指数（如 Artificial Analysis Intelligence Index）
     */
    private BigDecimal intelligenceIndex;

    /**
    * 输出速度（Token/秒，中位数）
     */
    private BigDecimal outputSpeedTokensPerSecond;

    /**
    * 延迟（秒，首 Token 中位耗时）
     */
    private BigDecimal latencyFirstTokenSeconds;

    /**
    * 上下文窗口大小（Token 数）
    *
    * <p>模型支持的最大上下文长度，如 262144 表示 256K tokens。
    * 来自数据源的 {@code contextWindowTokens} 字段。</p>
     */
    private Long contextWindowTokens;

    /**
    * 缓存命中输入价（USD / 百万 Token）
    *
    * <p>提示词缓存命中（cache read）时的输入单价，通常为正常输入价的一半。
    * 来自数据源的 {@code cacheHitPrice} 字段。</p>
     */
    private BigDecimal cacheHitPrice;

    /**
    * 缓存写入价（USD / 百万 Token）
    *
    * <p>提示词缓存写入（cache write）的单价，部分模型不提供写入价，此时为 null。
    * 来自数据源的 {@code cacheWritePrice} 字段。</p>
     */
    private BigDecimal cacheWritePrice;

    /**
    * 模型活跃参数量（十亿，B）
    *
    * <p>MoE 等模型为激活参数量而非总参数量，如 104 表示约 104B 活跃参数。
    * 来自数据源的 {@code activeParams} 字段。</p>
     */
    private BigDecimal activeParams;

    /**
    * 是否支持推理（思考）模式
    *
    * <p>推理模型在生成最终回复前会进行内部思考（Chain of Thought）。
    * 来自数据源的 {@code reasoningModel} 字段。</p>
     */
    private Boolean reasoning;

    /**
    * 思考等级
    *
    * <p>推理模型的思考强度档位，如 max / high / medium / low，
    * 不同档位影响推理深度与耗时。来自数据源的 {@code effort.slug} 字段。</p>
     */
    private String reasoningEffort;

    /**
    * 是否支持联网查询（内置搜索/在线检索）
    *
    * <p>数据源通常无显式字段，按模型名规律（search/online 等）推断，可被人工配置覆盖。</p>
     */
    private Boolean webSearch;

    /**
    * 是否支持图片识别（视觉输入）
    *
    * <p>数据源通常无显式字段，按模型名规律（vision、-v 结尾等）推断，可被人工配置覆盖。</p>
     */
    private Boolean imageInput;

    /**
    * 是否支持函数调用（工具调用）
    *
    * <p>来自数据源的 {@code features.functionCalling} 字段。</p>
     */
    private Boolean functionCalling;

    /**
    * 图片输入单价（USD / 张）
    *
    * <p>按图片张数计费的输入价格，多模态模型的图片输入费用。
    * 来自数据源的 {@code pricing.image} 字段（OpenRouter）。</p>
     */
    private BigDecimal imagePrice;

    /**
    * 网络检索单价（USD / 次）
    *
    * <p>每次联网检索（web search / browse）的固定费用。
    * 来自数据源的 {@code pricing.web_search} 字段（OpenRouter）。</p>
     */
    private BigDecimal webSearchPrice;

    /**
    * 端到端响应时间（秒，中位数）
    *
    * <p>从请求发出到收到完整响应的总耗时中位数。
    * 来自数据源的 {@code medianEndToEndResponseTimeSeconds} 字段（Artificial Analysis）。</p>
     */
    private BigDecimal endToEndResponseTimeSeconds;

    /**
    * 推理 Token 单价（USD / 百万 Token）
    *
    * <p>推理模型内部思考（CoT）消耗 token 的单价，独立于输出价。
    * 来自数据源的 {@code pricing.internal_reasoning} 字段（OpenRouter）。</p>
     */
    private BigDecimal internalReasoningPrice;

    /**
    * 输出模态列表
    *
    * <p>模型支持的输出类型，如 text / image / audio。
    * 来自数据源的 {@code architecture.output_modalities} 字段（OpenRouter）。</p>
     */
    private List<String> outputModalities;

    /**
    * 是否已废弃
    *
    * <p>模型是否已被官方标记为废弃/下线。
    * 来自数据源的 {@code deprecated} 字段（Artificial Analysis）。</p>
     */
    private Boolean deprecated;

    /**
    * 图标地址
     */
    private String iconUrl;
}
