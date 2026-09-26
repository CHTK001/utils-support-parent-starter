package com.chua.datasource.support.user;

/**
 * 数据源用户信息。
 *
 * @param user 用户名
 * @param host 主机名/IP
 * @param password 密码
 *
 * @author CH
 * @since 4.0.0.42
 */
public record UserInfo(
        String user,
        String host,
        String password
) {
}
