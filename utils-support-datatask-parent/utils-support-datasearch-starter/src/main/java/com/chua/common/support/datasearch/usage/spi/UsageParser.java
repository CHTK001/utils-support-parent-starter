package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;

import reactor.core.publisher.Flux;
import java.util.List;

/**
 * AI 用量解析器接口 — 解析外部 AI 工具本地存储的用量数据。
 *
 * <p>各工具（OpenCode、Codex 等）在本地存储了各自的用量信息，
 * UsageParser 负责从这些本地文件/配置中解析出标准化的 {@link AiUsage}。</p>
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
