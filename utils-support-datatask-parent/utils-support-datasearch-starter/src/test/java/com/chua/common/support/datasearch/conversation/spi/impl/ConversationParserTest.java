package com.chua.common.support.datasearch.conversation.spi.impl;

import com.chua.common.support.datasearch.conversation.ConversationMessage;
import com.chua.common.support.datasearch.conversation.spi.ConversationParser;
import com.chua.common.support.spi.ServiceProvider;

/**
 * Integration check for the ConversationParser subsystem.
 *
 * <p>Verifies SPI discovery of every implementation and streams a sample of
 * real chat records from local tool storage.</p>
 *
 * <p>Exit code 0 = passed; 1 = failed.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ConversationParserTest {

    /**
     * Runs SPI discovery and streaming verification.
     *
     * @param args optional provider names to sample; defaults to claude-code
     */
    public static void main(String[] args) {
        String[] names = {"claude-code", "qoder", "codebuddy"};
        int failures = 0;

        for (String name : names) {
            ConversationParser parser;
            try {
                parser = ServiceProvider.of(ConversationParser.class).getExtension(name);
            } catch (Exception e) {
                System.out.println("[FAIL] SPI[" + name + "] lookup threw: " + e.getMessage());
                failures++;
                continue;
            }
            if (parser == null) {
                System.out.println("[FAIL] SPI[" + name + "] not discovered");
                failures++;
                continue;
            }
            System.out.println("[PASS] SPI[" + name + "] discovered");

            long total = 0L;
            long userCount = 0L;
            long assistantCount = 0L;
            long textCount = 0L;
            long emptyText = 0L;
            try {
                for (ConversationMessage m : parser.streamMessages().toIterable()) {
                    total++;
                    if ("user".equals(m.getRole())) {
                        userCount++;
                    }
                    if ("assistant".equals(m.getRole())) {
                        assistantCount++;
                    }
                    if ("text".equals(m.getContentType())) {
                        textCount++;
                        if (m.getContent() == null || m.getContent().isBlank()) {
                            emptyText++;
                        }
                    }
                    if (total <= 3) {
                        System.out.printf("  sample: role=%s type=%s model=%s ts=%s text=%.60s%n",
                                m.getRole(), m.getContentType(), m.getModel(),
                                m.getTimestamp(),
                                m.getContent() == null ? "" : m.getContent());
                    }
                }
            } catch (Exception e) {
                System.out.println("[FAIL] stream threw: " + e.getMessage());
                failures++;
                continue;
            }

            boolean ok = total > 0 && textCount > 0 && userCount > 0 && assistantCount > 0;
            System.out.printf("%s %s: total=%d user=%d assistant=%d text=%d emptyText=%d%n",
                    ok ? "[PASS]" : "[FAIL]", name, total, userCount, assistantCount,
                    textCount, emptyText);
            if (!ok) {
                failures++;
            }
        }
        System.out.println(failures == 0 ? "[PASS] conversation layer verified"
                : "[FAIL] " + failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
