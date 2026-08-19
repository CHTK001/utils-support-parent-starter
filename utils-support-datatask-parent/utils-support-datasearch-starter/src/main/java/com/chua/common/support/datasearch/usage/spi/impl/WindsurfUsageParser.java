package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Windsurf 用量解析器 — 从 Windsurf 本地配置解析用量
 *
 * <p>数据源: %USERPROFILE%/.windsurf/</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("windsurf")
public class WindsurfUsageParser extends BaseUsageParser {

    /** Data_dir */
    private static final Path DATA_DIR = Path.of(System.getProperty("user.home"), ".windsurf");

    @Override
    /** Name */
    public String name() {
        return "windsurf";
    }

    @Override
    /** 解析All */
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(DATA_DIR)) {
            log.debug("[windsurf] 数据目录不存在: {}", DATA_DIR);
            return List.of();
        }
        // TODO: 解析 Windsurf 本地存储的用量数据
        log.debug("[windsurf] 数据目录存在，待实现解析逻辑");
        return List.of();
    }
}