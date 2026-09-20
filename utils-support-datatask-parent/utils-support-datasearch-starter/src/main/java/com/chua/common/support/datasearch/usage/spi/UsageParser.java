package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;

import reactor.core.publisher.Flux;
import java.util.List;

/**
 * AI 用量解析器接口 — 解析外部 AI 工具本地存储的用量数据。
 *
 * <p>各工具（OpenCode、Codex 等）在本地存储了各自的用量信息，
 * usageparser 负责从这些本地文件/配置中解析出标准化的 {@link AiUsage}。</p>
 *
 * <p>Token 口径——所有实现必须一致，{@link UsageFieldCompleter} 的缓存折扣依赖该口径：</p>
 * <ul>
 *   <li>{@code inputTokens}：本次请求的全部输入 Token，<b>包含</b>缓存命中部分；</li>
 *   <li>{@code cacheTokens}：其中命中缓存的输入 Token，必须满足 {@code cacheTokens <= inputTokens}；</li>
 *   <li>{@code outputTokens}：可见输出 Token，<b>不含</b>推理 Token；</li>
 *   <li>{@code reasoningTokens}：推理/思考 Token 单列，不得再算进 {@code outputTokens}；</li>
 *   <li>{@code totalTokens}：{@code inputTokens + outputTokens}，推理量不计入。</li>
 * </ul>
 *
 * <p>推理口径同样看本机分布判定：{@code reasoning > output} 一旦成立即说明两者互斥，原样分列即可
 * （gemini-cli 的 thoughts 达输出的 151 倍、qwen 15 倍、opencode 有 24% 的行越界）；
 * 反之若源自身把推理算作输出的明细，则必须净出后再产出——证据是 Copilot 的
 * {@code token_details_json} 只有 input/cache_read/cache_write/output 四类计价段且 output 段计数
 * 等于 {@code output_tokens}（380 含 reasoning 256）、zcode 的 {@code provider_total_tokens} 恰为
 * {@code input + output}（10611 + 431 = 11042，另有 reasoning 24）、codebuddy 的
 * {@code total_tokens = prompt + completion}（24964 + 17，其中 reasoning 14）。
 * 已按此净出的是 Codex 系（{@code reasoning_output_tokens}）、copilot-cli、vscode、codebuddy、
 * zcode、zed、hermes、lm-studio、unsloth、reasonix、workbuddy、grok；
 * 其中 zed、hermes 本机无推理数据，按与 Copilot 同形制的字段命名推定，出数异常时优先复核这两家。</p>
 *
 * <p>不这么做会怎样：{@code UsageFieldCompleter.calcOutputCost} 按
 * {@code outputTokens * 输出单价 + reasoningTokens * 推理单价} 计价，透传超集就会把推理量按输出价
 * 收两遍；净出之后，定价表缺少推理单价的模型（OpenRouter 447 个里 416 个）必须由输出单价兜底，
 * 否则净出的那段令牌会从账单里凭空消失。</p>
 *
 * <p>源字段并不统一，判定办法看本机分布：命中/输入中位数比接近 1 说明命中本就在输入之内，
 * 直接取用即可（claude-code 1.00、command-code 1.00、zcode 0.99、vscode 0.98、cline 0.91、
 * codex++ 0.90、codebuddy 1.00、copilot-cli 0.30，Codex 系的 {@code input_tokens} 亦含
 * {@code cached_input_tokens}）。比重大于输入、或源自身的 total 由"输入 + 输出 + 各缓存段"
 * 相加得到的，说明字段是互斥分段，必须先加回 {@code inputTokens} 再产出。已按此改写的有
 * opencode、openclaw、kilo、mimo、pi、dsh、kimi、kimi-code、omp、atomcode、grok、zed、
 * claude-science、doubao、ccswitch（该表按 {@code app_type} 混写两套口径，只有 opencode 行需要并回）
 * 以及 lm-studio、unsloth、reasonix、workbuddy（这几家原先在解析器内部自行"输入减缓存"）。</p>
 *
 * <p>不这么做会怎样：{@code cacheTokens > inputTokens} 时，{@link UsageFieldCompleter}
 * 只能按输入量封顶折算命中，超出部分既没按输入价也没按命中价计；把命中量当输入量扣减，
 * 则会把非缓存输入算少、整体费用低估。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface UsageParser {

    /**
     * 流式解析全部用量数据（响应式，支持背压）。
     *
     * <p>这是唯一的取数入口：实现必须以惰性、逐条方式产出，
     * 禁止一次性全量装载进内存。</p>
     *
     * @return 用量记录流
     */
    Flux<AiUsage> streamAll();

    /**
     * 当前解析器标识（如 "opencode"、"codex++"）。
     *
     * @return SPI 名称
     */
    String name();
}
