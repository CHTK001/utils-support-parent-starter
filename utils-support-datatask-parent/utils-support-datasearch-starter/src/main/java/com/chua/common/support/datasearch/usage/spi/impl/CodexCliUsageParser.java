package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Codex 官方 CLI 用量解析器 — 从 {@code %USERPROFILE%\.codex\sessions} 的 rollout JSONL 解析。
 *
 * <p>Codex 官方 CLI 与其 fork（acode / every-code）使用同一套转录格式：
 * 每行一个事件，{@code payload.type=token_count} 携带 {@code info.total_token_usage}
 * 累计用量，因此直接复用 {@link AbstractCodexForkRolloutUsageParser} 的口径处理
 * （输入含缓存、缓存写入并入输入、推理量净出后单列）。</p>
 *
 * <p>与 {@code codex++}（{@link CodexPlusPlusUsageParser}）的区别：后者读
 * {@code .codex/state_5.sqlite} 的 {@code threads} 汇总表，本解析器直接读转录，
 * 两者数据源不同、可并存；正式同步命中时以本解析器的逐轮记录为准。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("codex")
public class CodexCliUsageParser extends AbstractCodexForkRolloutUsageParser {

    /**
     * 解析器名称
     */
    private static final String PROVIDER = "codex";

    /**
     * 默认构造：解析当前用户主目录。
     */
    public CodexCliUsageParser() {
        super();
    }

    /**
     * 指定解析根目录构造。
     *
     * @param userHomes 逗号分隔的用户主目录列表
     */
    public CodexCliUsageParser(String userHomes) {
        super(userHomes);
    }

    @Override
    protected Path sessionsRoot(Path home) {
        return home.resolve(".codex");
    }

    @Override
    protected String providerName() {
        return PROVIDER;
    }

    @Override
    protected String defaultModel() {
        return "gpt-5";
    }
}
