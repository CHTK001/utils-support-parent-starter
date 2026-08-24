package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.UsageParser;

import java.util.List;

/**
 * Integration check runner for all UsageParser implementations.
 *
 * <p>Invokes every parser against its real local data source and prints a
 * summary table of record counts and token sums.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FinalTest {

    /**
     * Runs every parser and prints per-parser usage summary.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        test("opencode", new OpencodeUsageParser());
        test("claude-code", new ClaudeCodeUsageParser());
        test("codex++", new CodexPlusPlusUsageParser());
        test("vscode", new VscodeUsageParser());
        test("cursor-byok", new CursorByokUsageParser());
        test("cline", new ClineUsageParser());
        test("windsurf", new WindsurfUsageParser());
        test("continue", new ContinueUsageParser());
        test("gemini-cli", new GeminiCliUsageParser());
        test("cody", new CodyUsageParser());
        test("codebuddy", new CodeBuddyUsageParser());
        test("roocode", new RooCodeUsageParser());
        test("trae", new TraeUsageParser());
        test("trae-cn", new TraeCnUsageParser());
        test("augment", new AugmentUsageParser());
        test("ccswitch", new CcswitchUsageParser());
        test("zocde", new ZocdeUsageParser());
        test("joycode", new JoyCodeUsageParser());
        test("qoder", new QoderUsageParser());
    }

    /**
     * Streams all records for one parser and prints a summary line.
     *
     * @param name   parser display name
     * @param parser parser instance to run
     */
    static void test(String name, UsageParser parser) {
        List<AiUsage> records = parser.streamAll().collectList().block();
        long inputSum = 0L;
        long outputSum = 0L;
        double costSum = 0.0d;
        int estimatedCount = 0;
        for (AiUsage u : records) {
            if (u.getInputTokens() != null) {
                inputSum += u.getInputTokens();
            }
            if (u.getOutputTokens() != null) {
                outputSum += u.getOutputTokens();
            }
            if (u.getTotalCost() != null) {
                costSum += u.getTotalCost().doubleValue();
            }
            if (u.isEstimated()) {
                estimatedCount++;
            }
        }
        String estimatedTag = estimatedCount > 0 ? " (estimated=" + estimatedCount + ")" : "";
        String costTag = costSum > 0 ? String.format(" cost=%.4f", costSum) : "";
        System.out.printf("%-12s records=%-7d input=%-12d output=%-10d%s%s%n",
                name, records.size(), inputSum, outputSum, costTag, estimatedTag);
    }
}
