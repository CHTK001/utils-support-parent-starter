package com.chua.common.support.ai;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
* AI 服务用量信息
*
* <p>统一封装 AI 调用过程中产生的 Token 用量、费用和性能指标信息。
* 适用于对话（Chat）、图片生成（Image）、视频生成（Video）、Agent 等各类 AI 服务场景。
*
* <h3>在 Agent 系统中的角色</h3>
* <pre>
*   AgentResponse.getUsage()  → 返回 AiUsage
*     → 含主 Agent 的路由决策耗时
*     → 含子 Agent 的实际执行耗时（ ROUTER/AUTO 模式）
*     → 含 Token 用量、费用等
*
*   15 个 ChatClient 实现已填充 AiUsage：
*     OpenAI/Alibaba/Zhipu/Claude/Google/Baidu/Amazon/Microsoft/
*     Huawei/Doubao/Dingding/Xunfei/Qiniu/TencentHunyuan/Zai
* </pre>
*
* <p>本类按职责划分为五大区域：
* <ul>
*   <li><b>Token 用量</b> — 记录输入、输出、缓存等 Token 消耗</li>
*   <li><b>费用信息</b> — 记录单价和计算后的费用，支持多币种</li>
*   <li><b>性能指标</b> — 记录请求时间戳、耗时、首字耗时等性能数据</li>
* </ul>
*
* <p>使用示例：
* <pre>{@code
*   AiUsage usage = AiUsage.builder()
*       // Token 用量
*       .inputTokens(1200)
*       .outputTokens(800)
*       .totalTokens(2000)
*       .cacheTokens(200)
*       // 费用信息
*       .inputUnitPrice(new BigDecimal("0.005"))
*       .outputUnitPrice(new BigDecimal("0.015"))
*       .inputCost(new BigDecimal("0.006"))
*       .outputCost(new BigDecimal("0.012"))
*       .totalCost(new BigDecimal("0.018"))
*       .currency("USD")
*       .estimated(false)
*       // 性能指标
*       .startTime(System.currentTimeMillis())
*       .firstTokenLatencyMillis(320L)
*       .durationMillis(1200L)
*       .build();
* }</pre>
*
* @author CH
* @since 2026/07/16
 */
@Data
@Builder
public class AiUsage {

    // ==================== Token 用量 ====================

    /**
    * 输入 Token 数
    *
    * <p>本次请求中输入内容消耗的 Token 数量，包括：
    * <ul>
    *   <li>System Prompt（系统提示词）</li>
    *   <li>User Prompt（用户输入）</li>
    *   <li>历史对话消息（多轮对话上下文）</li>
    *   <li>图片/文件等多模态输入的 Token 折算</li>
    * </ul>
    *
    * <p>对于图片生成、视频生成等非文本对话服务，该字段可能为 null 或 0。
    */
    private Integer inputTokens;

    /**
    * 输出 Token 数
    *
    * <p>本次响应中模型生成内容消耗的 Token 数量，包括：
    * <ul>
    *   <li>模型回复文本</li>
    *   <li>工具调用（Tool Call）的参数和结果</li>
    *   <li>推理过程中的中间步骤 Token（如 CoT 思维链）</li>
    * </ul>
    *
    * <p>对于图片生成、视频生成等非文本对话服务，该字段可能为 null 或 0。
    */
    private Integer outputTokens;

    /**
    * 总 Token 数
    *
    * <p>输入与输出 Token 的总和，即 inputTokens + outputTokens。
    * 该字段为便捷汇总字段，便于快速判断总消耗是否超出配额限制。
    */
    private Integer totalTokens;

    /**
    * 缓存命中 Token 数
    *
    * <p>部分服务商（如 OpenAI、Anthropic）支持 Prompt Caching（提示词缓存），
    * 当连续多次请求的 System Prompt 或前缀相同时，缓存命中的部分无需重新计算。
    * 该字段记录本次请求中命中缓存的 Token 数量。
    *
    * <p>缓存命中的 Token 通常按较低费率计费（如 OpenAI 缓存命中价格为原价的 50%），
    * 因此实际费用可能低于按 totalTokens × 单价计算的理论值。
    *
    * <p>不支持缓存的服务商该字段为 null。
    */
    private Integer cacheTokens;

    /**
    * 缓存名称（Cache Name / Prompt Cache Key）
    *
    * <p>记录本次请求使用的提示词缓存标识，例如 OpenAI 自动生成的缓存前缀 ID
    * （如 {@code evl-...}），或自定义的缓存键名。
    *
    * <p>用于追溯缓存命中率、分析缓存预热效果，以及排查缓存未命中问题。
    * 不支持缓存或缓存未命中的请求该字段为 null。
    *
    * <p>与 {@link #cacheTokens} 配合使用：{@code cacheName != null && cacheTokens > 0}
    * 表示本次请求命中了指定缓存；{@code cacheName != null && cacheTokens == 0}
    * 表示请求触发了新缓存写入。
    */
    private String cacheName;

    // ==================== 费用信息 ====================

    /**
    * 输入单价（每 Token）
    *
    * <p>输入部分的单价，单位为货币单位/Token。
    *
    * <p>参考定价（以 OpenAI gpt-4o 为例）：
    * <ul>
    *   <li>输入单价：$0.005 / 1K tokens = 0.000005 / token</li>
    *   <li>输出单价：$0.015 / 1K tokens = 0.000015 / token</li>
    * </ul>
    *
    * <p>实现类应从服务商公开定价或 API 响应中解析并填充此字段。
    */
    private BigDecimal inputUnitPrice;

    /**
    * 输出单价（每 Token）
    *
    * <p>输出部分的单价，单位为货币单位/Token。
    * 通常输出单价高于输入单价（约为输入的 2~4 倍）。
    */
    private BigDecimal outputUnitPrice;

    /**
    * 输入费用
    *
    * <p>本次请求输入部分的实际费用，计算公式：inputTokens × inputUnitPrice。
    * 该字段由实现类根据单价和 Token 数自动计算填充。
    *
    * <p>如果服务商支持缓存折扣，此处应为实际扣除折扣后的费用。
    */
    private BigDecimal inputCost;

    /**
    * 输出费用
    *
    * <p>本次响应输出部分的实际费用，计算公式：outputTokens × outputUnitPrice。
    * 该字段由实现类根据单价和 Token 数自动计算填充。
    */
    private BigDecimal outputCost;

    /**
    * 总费用
    *
    * <p>本次调用的总费用，即 inputCost + outputCost。
    *
    * <p>特殊场景：
    * <ul>
    *   <li>对于图片/视频等按次计费的服务，该字段直接设置为固定金额</li>
    *   <li>对于部分免费额度的调用，该字段为 BigDecimal.ZERO</li>
    *   <li>对于不返回费用的服务商，该字段为 null</li>
    * </ul>
    */
    private BigDecimal totalCost;

    /**
    * 货币单位
    *
    * <p>费用字段的货币单位，遵循 ISO 4217 标准代码。
    * 常见值：
    * <ul>
    *   <li>"USD" — 美元（OpenAI、Anthropic 等默认币种）</li>
    *   <li>"CNY" — 人民币（国内服务商常用）</li>
    *   <li>"EUR" — 欧元</li>
    * </ul>
    *
    * <p>默认为 "USD"，实现类可根据服务商返回值覆盖。
    */
    @Builder.Default
    /** Currency */
    private String currency = "USD";

    /**
    * 费用是否为估算值
    *
    * <p>部分服务商不返回精确的费用数据，此时费用根据公开的单价信息计算得出，
    * 该标志位为 true 表示费用为估算值，仅供参考。
    *
    * <p>需要精确计费的场景（如用户账单、财务对账）应优先使用
    * 服务商返回的真实费用数据（当 estimated 为 false 时）。
    */
    @Builder.Default
    /** Estimated */
    private boolean estimated = false;

    // ==================== 模型标识 ====================

    /**
    * 模型名称
    *
    * <p>本次调用实际使用的模型 ID，如 "gpt-4o"、"claude-sonnet-4-20250514"、"deepseek-chat" 等。
    *
    * <p>该字段的用途：
    * <ul>
    *   <li>多模型计费 — 不同模型单价不同，需记录实际模型用于费用计算</li>
    *   <li>质量追溯 — 当输出质量异常时，可快速定位是哪个模型的问题</li>
    *   <li>AB 测试 — 对比不同模型在同一任务上的表现和成本</li>
    * </ul>
    *
    * <p>实现类应从 API 响应中解析并填充此字段。
    * 部分服务商在请求中指定的模型与实际使用的模型可能不同（如模型别名解析），
    * 此处应记录实际使用的模型。
    */
    private String model;

    /**
    * 服务商名称
    *
    * <p>本次调用的 AI 服务商标识，如 "openai"、"anthropic"、"deepseek"、"zhipu" 等。
    *
    * <p>同一模型可能通过不同服务商访问（如 OpenAI 直连 vs Azure OpenAI），
    * 价格和性能可能有差异，因此需要单独记录。
    */
    private String provider;

    // ==================== 请求追踪 ====================

    /**
    * 请求 ID
    *
    * <p>服务商返回的唯一请求标识，用于：
    * <ul>
    *   <li>日志关联 — 将用量数据与服务商侧的请求日志对齐</li>
    *   <li>问题排查 — 向服务商提交工单时提供 request_id 以加速定位</li>
    *   <li>幂等控制 — 部分场景下可用于防止重复计费</li>
    * </ul>
    *
    * <p>不同服务商的字段名可能不同（如 OpenAI 为 id，
    * Anthropic 为 request_id，国内服务商多为 requestId），
    * 实现类应统一映射到此字段。
    */
    private String requestId;

    // ==================== 生成状态 ====================

    /**
    * 停止原因
    *
    * <p>模型停止生成的原因，常见取值：
    * <ul>
    *   <li>"stop" — 模型正常结束（输出了结束标记）</li>
    *   <li>"length" — 达到 max_tokens 上限，输出被截断</li>
    *   <li>"content_filter" — 输出被内容安全策略过滤</li>
    *   <li>"tool_calls" — 模型请求调用工具，暂停生成等待工具结果</li>
    *   <li>"max_tokens" — 等同于 length，部分服务商使用此值</li>
    * </ul>
    *
    * <p>业务侧可根据此字段判断是否需要继续处理（如 length 时提示用户缩短输入）。
    */
    private String finishReason;

    // ==================== 推理 Token ====================

    /**
    * 推理 Token 数
    *
    * <p>部分推理模型（如 OpenAI o1、o3，DeepSeek R1）在生成最终回复前会进行内部推理（Chain of Thought），
    * 推理过程消耗的 Token 单独统计。
    *
    * <p>计费规则（以 OpenAI o1 为例）：
    * <ul>
    *   <li>推理 Token 按输出单价计费，但通常有独立的较高费率</li>
    *   <li>推理 Token 不计入 outputTokens，需单独追踪</li>
    * </ul>
    *
    * <p>非推理模型该字段为 null。
    */
    private Integer reasoningTokens;

    // ==================== 性能指标 ====================

    /**
    * 请求开始时间（Unix 毫秒时间戳）
    *
    * <p>记录本次 AI 调用发起的时间点，即客户端发送 HTTP 请求的时刻。
    * 该字段可用于：
    * <ul>
    *   <li>日志关联 — 将用量记录与请求日志按时间对齐</li>
    *   <li>时序分析 — 按时间维度聚合调用频次和费用趋势</li>
    *   <li>超时判断 — 结合 durationMillis 判断是否接近超时阈值</li>
    * </ul>
    *
    * <p>建议使用 {@link System#currentTimeMillis()} 填充。
    */
    private Long startTime;

    /**
    * 首字耗时（毫秒）
    *
    * <p>从请求发出到收到服务端返回的第一个 Token（First Token）所经过的时间，
    * 也称为 TTFT（Time To First Token）。
    *
    * <p>该指标是衡量 AI 服务响应速度的核心指标：
    * <ul>
    *   <li>流式场景：首字耗时直接影响用户感知的"等待时间"</li>
    *   <li>非流式场景：该字段可能等于 durationMillis 或为 null</li>
    * </ul>
    *
    * <p>参考基准（GPT-4o）：
    * <ul>
    *   <li>优秀：&lt; 300ms</li>
    *   <li>良好：300ms ~ 800ms</li>
    *   <li>一般：800ms ~ 2000ms</li>
    *   <li>较差：&gt; 2000ms</li>
    * </ul>
    */
    private Long firstTokenLatencyMillis;

    /**
    * 总耗时（毫秒）
    *
    * <p>从请求发出到收到完整响应所经过的总时间，即端到端延迟。
    *
    * <p>计算方式：durationMillis = 响应完成时刻 - startTime。
    *
    * <p>该字段与 {@link #firstTokenLatencyMillis} 的关系：
    * <ul>
    *   <li>流式响应：durationMillis 包含了首字耗时 + 生成全部内容的耗时</li>
    *   <li>非流式响应：durationMillis 等于整个请求的往返延迟</li>
    *   <li>图片/视频异步任务：该字段可能为提交任务的耗时，而非生成耗时</li>
    * </ul>
    */
    private Long durationMillis;

    // ==================== 频率限制 ====================

    /**
    * 剩余调用次数
    *
    * <p>当前 API Key 在当前限流窗口内剩余的可调用次数。
    * 服务商通常在 HTTP 响应头中返回此信息（如 X-RateLimit-Remaining）。
    *
    * <p>业务侧可根据此字段实现主动限流保护：
    * <ul>
    *   <li>当剩余次数低于阈值时，降低调用频率或提示用户</li>
    *   <li>避免因超出频率限制导致请求被拒绝（HTTP 429）</li>
    * </ul>
    *
    * <p>不返回此信息的服务商该字段为 null。
    */
    private Integer rateLimitRemaining;

    /**
    * 频率限制重置时间（Unix 毫秒时间戳）
    *
    * <p>当前限流窗口的重置时刻，超过此时间后调用次数配额将恢复。
    * 配合 {@link #rateLimitRemaining} 使用，可在配额耗尽时精确计算等待时间。
    *
    * <p>不返回此信息的服务商该字段为 null。
    */
    private Long rateLimitReset;
}
