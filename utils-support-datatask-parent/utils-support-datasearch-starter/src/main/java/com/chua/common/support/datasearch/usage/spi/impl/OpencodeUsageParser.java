package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OpenCode 用量解析器 — 从 Electron 桌面端日志解析
 *
 * <p>数据源: %APPDATA%/ai.opencode.desktop/logs/</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("opencode")
public class OpencodeUsageParser extends BaseUsageParser {

    /** Logs_dir */
    private static final Path LOGS_DIR = Path.of(
            System.getProperty("user.home"), "AppData", "Roaming", "ai.opencode.desktop", "logs");

    @Override
    public String name() {
        return "opencode";
    }

    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(LOGS_DIR)) {
            log.debug("[opencode] 日志目录不存在: {}", LOGS_DIR);
            return List.of();
        }
        // TODO: 解析 Electron leveldb / 日志中的 token 用量
        log.debug("[opencode] 日志目录存在，待实现解析逻辑");
        return List.of();
    }
}
