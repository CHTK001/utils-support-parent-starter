package com.chua.datasource.support.permission;

import java.util.List;

/**
 * 权限管理器 SPI 接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface PermissionManager {

    /**
     * 返回 SPI 扩展键（如 "MySQL"、"PostgreSQL"）。
     * @return 结果字符串
     */
    String type();

    /**
     * 列出所有权限记录。
     *
     * @return 权限信息列表
     */
    List<PermissionInfo> listPermissions();

    /**
     * 列出指定用户的所有权限。
     *
     * @param username 用户名
     * @return 权限信息列表
     */
    List<PermissionInfo> listPermissions(String username);

    /**
     * 授予权限步骤接口。
     * @author CH
     * @since 4.0.0
     */
    interface GrantStep {
    /**
     * 指定被授权的用户。
     *
     * @param username 用户名
     * @return 当前步骤，便于链式调用
     */
        GrantStep toUser(String username);
    /**
     * 指定操作的数据库。
     *
     * @param database 数据库名
     * @return 当前步骤，便于链式调用
     */
        GrantStep onDatabase(String database);
    /**
     * 指定操作的表。
     *
     * @param table 表名
     * @return 当前步骤，便于链式调用
     */
        GrantStep onTable(String table);
    /**
     * 指定操作的表与列。
     *
     * @param table 表名
     * @param column 列名
     * @return 当前步骤，便于链式调用
     */
        GrantStep onColumn(String table, String column);
    /**
     * 设置是否允许转授权。
     *
     * @param grantable 是否允许转授权
     * @return 当前步骤，便于链式调用
     */
        GrantStep withGrantOption(boolean grantable);
    /**
     * 执行已配置的操作。
     */
        void execute();
    }

    /**
     * 撤销权限步骤接口。
     * @author CH
     * @since 4.0.0
     */
    interface RevokeStep {
    /**
     * 指定被回收权限的用户。
     *
     * @param username 用户名
     * @return 当前步骤，便于链式调用
     */
        RevokeStep fromUser(String username);
    /**
     * 指定操作的数据库。
     *
     * @param database 数据库名
     * @return 当前步骤，便于链式调用
     */
        RevokeStep onDatabase(String database);
    /**
     * 指定操作的表。
     *
     * @param table 表名
     * @return 当前步骤，便于链式调用
     */
        RevokeStep onTable(String table);
    /**
     * 指定操作的表与列。
     *
     * @param table 表名
     * @param column 列名
     * @return 当前步骤，便于链式调用
     */
        RevokeStep onColumn(String table, String column);
    /**
     * 执行已配置的操作。
     */
        void execute();
    }

    /**
     * 授予权限。
     *
     * @param privileges 权限列表，如 {@code "SELECT, INSERT"}
     * @return 授权步骤
     */
    GrantStep grant(String privileges);

    /**
     * 撤销权限。
     *
     * @param privileges 权限列表，如 {@code "SELECT"}
     * @return 撤销步骤
     */
    RevokeStep revoke(String privileges);
}
