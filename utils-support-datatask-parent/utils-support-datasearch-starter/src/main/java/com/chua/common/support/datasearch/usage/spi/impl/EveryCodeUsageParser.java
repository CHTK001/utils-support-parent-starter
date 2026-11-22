package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;
import com.chua.common.support.utils.StringUtils;

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

    /**
     * 默认构造：解析当前用户主目录。
     */
    public EveryCodeUsageParser() {
        super();
    }

    /**
     * 指定解析根目录构造。
     *
     * @param userHomes 逗号分隔的用户主目录列表
     */
    public EveryCodeUsageParser(String userHomes) {
        super(userHomes);
    }

    @Override
    protected Path sessionsRoot(Path home) {
        String override = System.getenv("CODE_HOME");
        return (StringUtils.isNotBlank(override))
                ? Path.of(override)
                : home.resolve(".code");
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
