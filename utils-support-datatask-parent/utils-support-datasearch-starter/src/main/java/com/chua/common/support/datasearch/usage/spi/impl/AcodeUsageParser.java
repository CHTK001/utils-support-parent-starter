package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * AStudio (acode) 用量解析器。
 *
 * <p>数据源为 {@code ~/.acode/sessions}（{@code TOKENTRACKER_ACODE_HOME} 覆盖），
 * Codex-fork rollout JSONL 格式。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("acode")
public class AcodeUsageParser extends AbstractCodexForkRolloutUsageParser {

    private final Path sessionsRoot;

    /**
     * 默认构造器。
    */
    public AcodeUsageParser() {
        String home = System.getenv("TOKENTRACKER_ACODE_HOME");
        Path root = (home != null && !home.isBlank())
                ? Path.of(home)
                : Path.of(System.getProperty("user.home"), ".acode");
        this.sessionsRoot = root;
    }

    @Override
    protected Path sessionsRoot() {
        return sessionsRoot;
    }

    @Override
    protected String providerName() {
        return "acode";
    }

    @Override
    protected String defaultModel() {
        return "acode-unknown";
    }
}