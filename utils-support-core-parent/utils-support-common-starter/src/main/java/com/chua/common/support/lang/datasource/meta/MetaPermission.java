package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.PermissionDef;

import java.util.List;

/**
* 权限元数据操作接口。
* <p>
* 提供数据库用户权限的查询、授予、撤销等链式操作。
* </p>
* <p>
* 使用示例：
* <pre>{@code
* // 列出指定用户的所有权限
* List<PermissionDef> perms = engine.meta().permission().onUser("app_user").list();
*
* // 授予表级权限
* engine.meta().permission()
*     .onTable("orders")
*     .grant("SELECT, INSERT, UPDATE")
*     .toUser("app_user")
*     .execute();
*
* // 撤销列级权限
* engine.meta().permission()
*     .onColumn("orders", "salary")
*     .revoke("UPDATE")
*     .fromUser("app_user")
*     .execute();
*
* // 列出所有用户的权限
* List<PermissionDef> all = engine.meta().permission().list();
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface MetaPermission {

    /**
    * 列出当前数据库下的所有权限记录。
    *
    * @return 权限定义列表
     */
    List<PermissionDef> list();

    /**
    * 列出指定用户的所有权限。
    *
    * @param username 用户名
    * @return 权限定义列表
     */
    List<PermissionDef> listByUser(String username);

    /**
    * 指定目标用户进行授权/取消授权操作。
    *
    * @param username 用户名
    * @return this
     */
    MetaPermission toUser(String username);

    /**
    * 指定目标表进行授权/取消授权操作。
    *
    * @param tableName 表名
    * @return this
     */
    MetaPermission onTable(String tableName);

    /**
    * 指定目标列进行授权/取消授权操作。
    *
    * @param tableName 表名
    * @param columnName 列名
    * @return this
     */
    MetaPermission onColumn(String tableName, String columnName);

    /**
    * 授予权限（链式构建器）。
    *
    * @param privileges 权限列表，如 {@code "SELECT, INSERT"}
    * @return 授予权限构建器
     */
    GrantBuilder grant(String privileges);

    /**
    * 撤销权限（链式构建器）。
    *
    * @param privileges 权限列表，如 {@code "SELECT"}
    * @return 撤销权限构建器
     */
    RevokeBuilder revoke(String privileges);
}
