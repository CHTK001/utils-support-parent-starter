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
        GrantStep toUser(String username);
        GrantStep onDatabase(String database);
        GrantStep onTable(String table);
        GrantStep onColumn(String table, String column);
        GrantStep withGrantOption(boolean grantable);
        void execute();
    }

    /**
    * 撤销权限步骤接口。
    * @author CH
    * @since 4.0.0
    */
    interface RevokeStep {
        RevokeStep fromUser(String username);
        RevokeStep onDatabase(String database);
        RevokeStep onTable(String table);
        RevokeStep onColumn(String table, String column);
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
