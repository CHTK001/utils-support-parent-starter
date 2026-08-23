package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OpenCode 用量解析器 — 从本地存储目录解析
 *
 * <p>数据源: %USERPROFILE%\.local\share\opencode\
 * <ul>
 *   <li>项目位于 Git 仓库时: &lt;project-slug&gt;/storage/</li>
 *   <li>非 Git 仓库: global/storage/</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("opencode")
public class OpencodeUsageParser extends BaseUsageParser {

    /** Data_dir */
    private static final Path DATA_DIR = Path.of(
            System.getProperty("user.home"), ".local", "share", "opencode");

    @Override
    /** Name */
    public String name() {
        return "opencode";
    }

    @Override
    /** 解析All */
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(DATA_DIR)) {
            log.debug("[opencode] 数据目录不存在: {}", DATA_DIR);
            return List.of();
        }
        // TODO: 解析 project/<slug>/storage/ 或 global/storage/ 中的会话数据
        log.debug("[opencode] 数据目录存在，待实现解析逻辑");
        return List.of();
    }
}
