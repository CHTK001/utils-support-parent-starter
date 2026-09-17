package com.chua.common.support.lang.datasource.meta;

/**
* 授予权限链式构建器。
*
* @author CH
* @since 4.0.0.42
 */
public interface GrantBuilder {

    /**
    * 指定授权目标用户。
    *
    * @param username 用户名
    * @return this
    */
    GrantBuilder toUser(String username);

    /**
    * 执行授予权限语句。
    *
    * @return true 授予成功
    */
    boolean execute();
}
