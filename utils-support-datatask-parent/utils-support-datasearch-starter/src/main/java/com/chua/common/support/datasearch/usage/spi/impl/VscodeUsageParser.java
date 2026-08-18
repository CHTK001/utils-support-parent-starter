package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * VSCode 用量解析器 — 从 GitHub Copilot 本地存储解析用量
 *
 * <p>数据源: %APPDATA%/Code/User/globalStorage/github.copilot-chat/</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("vscode")
public class VscodeUsageParser extends BaseUsageParser {

    /** Data_dir */
    private static final Path DATA_DIR = Path.of(
            System.getProperty("user.home"), "AppData", "Roaming", "Code", "User",
            "globalStorage", "github.copilot-chat");

    @Override
    public String name() {
        return "vscode";
    }

    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(DATA_DIR)) {
            log.debug("[vscode] Copilot 数据目录不存在: {}", DATA_DIR);
            return List.of();
        }
        // TODO: 解析 VSCode Copilot 本地存储的用量数据
        log.debug("[vscode] Copilot 数据目录存在，待实现解析逻辑");
        return List.of();
    }
}
