package com.chua.datasource.support.permission;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
* 权限信息，描述一个用户对数据库对象的访问权限。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionInfo {

    /** 用户名 */
    private String user;
    /** 主机/IP */
    private String host;
    /** 权限类型（TABLE / COLUMN / DATABASE / 全局） */
    private String privilegeType;
    /** 数据库名 */
    private String databaseName;
    /** 表名 */
    private String tableName;
    /** 列名（COLUMN 类型时有效） */
    private String columnName;
    /** 权限名（选择 / 插入 / 更新 / 删除 / 全部 等） */
    private String privilege;
    /** 是否可转授 */
    private boolean grantable;
}
