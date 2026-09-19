package com.chua.common.support.lang.datasource.flyway;

/**
 * 迁移脚本信息。
 *
 * @param version     迁移版本号（脚本名 {@code V{N}} 的 N）
 * @param description 迁移描述（脚本名中的描述部分）
 * @param script      脚本文件名
 * @param applied     是否已应用
 * @author CH
 * @since 4.0.0.42
 */
public record MigrationInfo(
        long version,
        String description,
        String script,
        boolean applied
) {
}