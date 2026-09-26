package com.chua.datasource.support.permission;

/**
 * 权限信息，描述一个用户对数据库对象的访问权限。
 *
 * @param user 用户名
 * @param host 主机/IP
 * @param privilegeType 权限类型（TABLE / COLUMN / DATABASE / 全局）
 * @param databaseName 数据库名
 * @param tableName 表名
 * @param columnName 列名（COLUMN 类型时有效）
 * @param privilege 权限名（选择 / 插入 / 更新 / 删除 / 全部
 *                  等）
 * @param grantable 是否可转授
 *
 * @author CH
 * @since 4.0.0.42
 */
public record PermissionInfo(
        String user,
        String host,
        String privilegeType,
        String databaseName,
        String tableName,
        String columnName,
        String privilege,
        boolean grantable
) {
}
