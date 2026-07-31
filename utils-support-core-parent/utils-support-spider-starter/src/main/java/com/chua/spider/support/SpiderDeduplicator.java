package com.chua.spider.support;

/**
 * 爬虫去重器（占位说明）。
 *
 * <p>URL 去重功能统一使用
 * {@link com.chua.common.support.task.deduplicate.Deduplicator} SPI 接口，
 * 不再单独定义去重接口。
 *
 * <p>默认实现：{@link com.chua.common.support.task.deduplicate.MemoryDeduplicator}
 *
 * @see com.chua.common.support.task.deduplicate.Deduplicator
 * @see com.chua.common.support.task.deduplicate.MemoryDeduplicator
 *
 * @author CH
*/
public final class SpiderDeduplicator {

    /**
     * 工具类防实例化。
     */
    private SpiderDeduplicator() {
    }
}
