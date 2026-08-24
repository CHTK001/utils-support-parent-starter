package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CC Switch usage parser.
 *
 * <p>CC Switch (github.com/farion1231/cc-switch) is a desktop configuration
 * switcher for Claude Code / Codex. It does NOT proxy or log API calls —
 * its only job is rewriting provider configs:</p>
 *
 * <ul>
 *   <li>{@code ~/.claude/settings.json} — active Anthropic-compatible endpoint</li>
 *   <li>{@code ~/.codex/auth.json} + {@code config.toml} — active Codex endpoint</li>
 * </ul>
 *
 * <p>All token usage generated "through" CC Switch is therefore written by
 * Claude Code / Codex themselves into their own storage:</p>
 *
 * <ul>
 *   <li>Claude Code sessions → covered by {@link ClaudeCodeUsageParser}</li>
 *   <li>Codex sessions → covered by {@link CodexPlusPlusUsageParser}</li>
 * </ul>
 *
 * <p>This parser verifies whether CC Switch is installed and reports zero
 * records of its own, since any usage is already captured by the parsers
 * above. Installing CC Switch never duplicates usage data.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ccswitch")
public class CcswitchUsageParser extends BaseUsageParser {

    private static final Path CCSWITCH_DIR = Path.of(System.getProperty("user.home"), ".cc-switch");

    /**
     * Returns the SPI name for CC Switch.
     *
     * @return {@code "ccswitch"}
     */
    @Override
    public String name() {
        return "ccswitch";
    }

    /**
     * Reports CC Switch installation state without producing usage records.
     *
     * <p>CC Switch stores no usage data locally; all usage made through its
     * switched providers is persisted by Claude Code / Codex and parsed by
     * {@code claude-code} / {@code codex++} parsers.</p>
     *
     * @return always an empty list
     */
    @Override
    public List<AiUsage> parseAll() {
        if (!Files.isDirectory(CCSWITCH_DIR)) {
            log.debug("[ccswitch] CC Switch not installed (no ~/.cc-switch)");
            return List.of();
        }
        log.debug("[ccswitch] installed; usage is tracked by claude-code/codex++ parsers");
        return List.of();
    }
}
