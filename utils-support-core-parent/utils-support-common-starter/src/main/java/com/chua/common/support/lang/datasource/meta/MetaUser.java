package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.UserDef;

import java.util.List;

/**
* 用户元数据操作接口。
* <p>
* 提供数据库用户的查询、创建、修改、删除等链式操作。
* </p>
* <p>
* 使用示例：
* <pre>{@code
* // 列出所有用户
* List<UserDef> users = engine.meta().user().list();
*
* // 创建用户
* engine.meta().user()
*     .create("app_user")
*     .withPassword("secret")
*     .withHost("127.0.0.1")
*     .execute();
*
* // 授予权限
* engine.meta().permission()
*     .onTable("user")
*     .grant("SELECT, INSERT")
*     .toUser("app_user")
*     .execute();
*
* // 删除用户
* boolean dropped = engine.meta().user().drop("app_user");
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface MetaUser {

    /**
    * 列出当前数据库下的所有用户。
    *
    * @return 用户定义列表
     */
    List<UserDef> list();

    /**
    * 创建用户（链式构建器）。
    *
    * @param username 用户名
    * @return 创建用户构建器
     */
    UserCreateBuilder create(String username);

    /**
    * 修改用户（链式构建器）。
    *
    * @param username 用户名
    * @return 修改用户构建器
     */
    UserAlterBuilder alter(String username);

    /**
    * 删除用户。
    *
    * @param username 用户名
    * @return true 删除成功
     */
    boolean drop(String username);
}
