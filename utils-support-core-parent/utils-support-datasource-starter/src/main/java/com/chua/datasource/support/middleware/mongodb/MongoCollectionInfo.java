package com.chua.datasource.support.middleware.mongodb;

/**
 * 集合信息。
 *
 * @param name           集合名
 * @param type           集合类型，缺省 {@code collection}
 * @param estimatedCount 估算文档数；权限不足或取不到时为 {@code null}
 * @author CH
 * @since 4.0.0.51
 */
public record MongoCollectionInfo(String name, String type, Long estimatedCount) {
}
