package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Every Code 用量解析器。
 *
 * <p>数据源为 {@code ~/.code/sessions}（{@code CODE_HOME} 覆盖），
 * Codex-fork rollout JSONL 格式。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("every-code")
public class EveryCodeUsageParser extends AbstractCodexForkRolloutUsageParser {

    private final Path sessionsRoot;

    /** 默认构造器。 */
    public EveryCodeUsageParser() {
        String home = System.getenv("CODE_HOME");
        Path root = (home != null && !home.isBlank())
                ? Path.of(home)
                : Path.of(System.getProperty("user.home"), ".code");
        this.sessionsRoot = root;
    }

    @Override
    protected Path sessionsRoot() {
        return sessionsRoot;
    }

    @Override
    protected String providerName() {
        return "every-code";
    }

    @Override
    protected String defaultModel() {
        return "every-code-unknown";
    }
}