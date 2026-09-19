package com.chua.common.support.lang.datasource.meta;

/**
 * 创建用户链式构建器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface UserCreateBuilder {

    /**
     * 设置用户密码。
     *
     * @param password 密码
     * @return this
     */
    UserCreateBuilder withPassword(String password);

    /**
     * 设置用户可登录的主机（IP 或 %）。
     *
     * @param host 主机名或 IP
     * @return this
     */
    UserCreateBuilder withHost(String host);

    /**
     * 执行创建用户语句。
     *
     * @return true 创建成功
     */
    boolean execute();
}
