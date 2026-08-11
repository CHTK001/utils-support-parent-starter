package com.chua.common.support.scattergather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScatterGatherDeduplicator 去重器测试。
 *
 * @author CH
 */
class ScatterGatherDeduplicatorTest {

    @Test
    void testDuplicateDetection() {
        ScatterGatherDeduplicator dedup = new ScatterGatherDeduplicator(5000);
        assertFalse(dedup.isDuplicate("req-1"));
        assertTrue(dedup.isDuplicate("req-1"));
        dedup.close();
    }

    @Test
    void testNullRequestId() {
        ScatterGatherDeduplicator dedup = new ScatterGatherDeduplicator(5000);
        assertFalse(dedup.isDuplicate(null));
        assertFalse(dedup.isDuplicate(""));
        dedup.close();
    }

    @Test
    void testMarkProcessed() {
        ScatterGatherDeduplicator dedup = new ScatterGatherDeduplicator(5000);
        dedup.markProcessed("req-2");
        assertTrue(dedup.isDuplicate("req-2"));
        dedup.close();
    }

    @Test
    void testExpiry() throws InterruptedException {
        ScatterGatherDeduplicator dedup = new ScatterGatherDeduplicator(100);
        assertFalse(dedup.isDuplicate("req-3"));
        Thread.sleep(200);
        // 过期后应返回 false
        assertFalse(dedup.isDuplicate("req-3"));
        dedup.close();
    }
}