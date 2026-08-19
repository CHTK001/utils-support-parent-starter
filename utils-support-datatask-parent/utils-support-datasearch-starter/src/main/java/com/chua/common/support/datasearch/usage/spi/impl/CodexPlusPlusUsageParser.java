package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Codex++ 用量解析器 — 从 Codex 本地 SQLite + 配置解析用量
 *
 * <p>数据源: %USERPROFILE%/.codex/</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("codex++")
public class CodexPlusPlusUsageParser extends BaseUsageParser {

    /** Data_dir */
    private static final Path DATA_DIR = Path.of(System.getProperty("user.home"), ".codex");

    @Override
    /** Name */
    public String name() {
        return "codex++";
    }

    @Override
    /** 解析All */
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(DATA_DIR)) {
            log.debug("[codex++] 数据目录不存在: {}", DATA_DIR);
            return List.of();
        }
        // TODO: 解析 .codex/logs_2.sqlite 中的用量数据
        log.debug("[codex++] 数据目录存在，待实现 SQLite 解析逻辑");
        return List.of();
    }
}
