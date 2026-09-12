package com.chua.common.support.lang.datasource.meta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
* 用户定义，描述数据库中的一个用户账号。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDef {

    /**
    * 用户名
     */
    private String user;

    /**
    * 主机/IP（如 {@code localhost}、{@code 127.0.0.1}、{@code %}）
     */
    private String host;

    /**
    * 认证插件（如 mysql_native_password、caching_sha2_password）
     */
    private String authenticationPlugin;

    /**
    * 账号是否锁定（true = 锁定）
     */
    private boolean locked;

    /**
    * 账号过期时间
     */
    private String expireDate;

    /**
    * 密码上次修改时间
     */
    private String passwordLastChanged;
}
