package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;
import com.chua.common.support.utils.StringUtils;

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

    /**
     * 默认构造：解析当前用户主目录。
     */
    public AcodeUsageParser() {
        super();
    }

    /**
     * 指定解析根目录构造。
     *
     * @param userHomes 逗号分隔的用户主目录列表
     */
    public AcodeUsageParser(String userHomes) {
        super(userHomes);
    }

    @Override
    protected Path sessionsRoot(Path home) {
        String override = System.getenv("TOKENTRACKER_ACODE_HOME");
        return (StringUtils.isNotBlank(override))
                ? Path.of(override)
                : home.resolve(".acode");
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
