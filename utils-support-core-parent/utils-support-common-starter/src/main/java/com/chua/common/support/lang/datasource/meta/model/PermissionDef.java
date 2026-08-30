package com.chua.common.support.lang.datasource.meta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 权限定义，描述用户对数据库对象的访问权限。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionDef {

    /**
     * 用户名
     */
    private String user;

    /**
     * 主机/IP
     */
    private String host;

    /**
     * 权限类型（TABLE / COLUMN / DATABASE / GLOBAL）
     */
    private String privilegeType;

    /**
     * 被授权的数据库名
     */
    private String databaseName;

    /**
     * 被授权的表名（TABLE 类型时有效）
     */
    private String tableName;

    /**
     * 被授权的列名（COLUMN 类型时有效）
     */
    private String columnName;

    /**
     * 权限名称（SELECT / INSERT / UPDATE / DELETE / ALL 等）
     */
    private String privilege;

    /**
     * 是否授予选项（可转授给其他用户，仅 MySQL GRANT 时使用）
     */
    private boolean grantable;
}
