package com.chua.datasource.support.middleware.mongodb;

/**
 * 逻辑库信息。
 *
 * @param name       逻辑库名
 * @param sizeOnDisk 磁盘占用（字节），服务端未提供时为 {@code null}
 * @param empty      是否空库，服务端未提供时为 {@code null}
 * @author CH
 * @since 4.0.0.51
 */
public record MongoDatabaseInfo(String name, Long sizeOnDisk, Boolean empty) {
}
