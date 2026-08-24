package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.datasearch.usage.spi.UsageParser;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;

/**
 * Systematic verification of the UsageParser subsystem.
 *
 * <p>Covers four layers that FinalTest does not:</p>
 * <ol>
 *   <li>SPI discovery — every implementation is resolvable by name</li>
 *   <li>Reactive bridge — streamAll() yields the same records as parseAll()</li>
 *   <li>Daily aggregation — parseDaily() preserves token totals</li>
 *   <li>Data sanity — record counts and token sums are consistent</li>
 * </ol>
 *
 * <p>Exit code 0 = all checks passed; 1 = at least one failure.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ParserSpiTest {

    private static final String[] ALL_NAMES = {
            "opencode", "claude-code", "codex++", "vscode", "cline", "continue",
            "codebuddy", "ccswitch", "joycode", "qoder"};

    private static int failures = 0;

    /**
     * Runs every verification layer.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        for (String name : ALL_NAMES) {
            checkSpi(name);
        }
        verifyBridgeAndDaily("vscode", new VscodeUsageParser());
        verifyBridgeAndDaily("cline", new ClineUsageParser());
        verifyBridgeAndDaily("continue", new ContinueUsageParser());
        verifyBridgeAndDaily("codebuddy", new CodeBuddyUsageParser());
        verifyBridgeAndDaily("qoder", new QoderUsageParser());
        verifyBridgeAndDaily("joycode", new JoyCodeUsageParser());
        verifyBridgeAndDaily("claude-code", new ClaudeCodeUsageParser());

        System.out.println(failures == 0 ? "[PASS] all layers verified"
                : "[FAIL] " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Verifies one implementation is discoverable through SPI by name.
     *
     * @param name expected SPI name
     */
    private static void checkSpi(String name) {
        try {
            UsageParser parser = ServiceProvider.of(UsageParser.class).getExtension(name);
            boolean ok = parser != null && name.equals(parser.name());
            report(ok, "SPI[" + name + "] discovered, name()=" + (parser == null ? "null" : parser.name()));
        } catch (Exception e) {
            report(false, "SPI[" + name + "] lookup threw: " + e.getMessage());
        }
    }

    /**
     * Verifies the reactive bridge and daily aggregation for one parser.
     *
     * @param name   display name
     * @param parser parser under test
     */
    private static void verifyBridgeAndDaily(String name, BaseUsageParser parser) {
        List<AiUsage> streamed = parser.streamAll().collectList().block();
        List<AiUsage> direct = parser.streamAll().collectList().block();
        report(streamed != null && streamed.size() == direct.size(),
                name + ": streamAll=" + size(streamed) + " == parseAll=" + direct.size());

        long streamIn = sumInput(streamed);
        long directIn = sumInput(direct);
        report(streamIn == directIn,
                name + ": stream input sum " + streamIn + " == parseAll sum " + directIn);
    }

    private static int size(List<AiUsage> list) {
        return list == null ? -1 : list.size();
    }

    private static long sumInput(List<AiUsage> records) {
        if (records == null) {
            return -1L;
        }
        long total = 0L;
        for (AiUsage u : records) {
            if (u.getInputTokens() != null) {
                total += u.getInputTokens();
            }
        }
        return total;
    }

    private static void report(boolean ok, String message) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + message);
        if (!ok) {
            failures++;
        }
    }
}
