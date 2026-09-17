package com.chua.common.support.lang.datasource.meta;

/**
* 修改用户链式构建器。
*
* @author CH
* @since 4.0.0.42
 */
public interface UserAlterBuilder {

    /**
    * 修改用户密码。
    *
    * @param password 新密码
    * @return this
    */
    UserAlterBuilder withPassword(String password);

    /**
    * 修改用户可登录的主机。
    *
    * @param host 主机名或 IP
    * @return this
    */
    UserAlterBuilder withHost(String host);

    /**
    * 执行修改用户语句。
    *
    * @return true 修改成功
    */
    boolean execute();
}
