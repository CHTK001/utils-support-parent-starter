package com.chua.datasource.support.user;

import java.util.List;

/**
 * 用户管理器 SPI 接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface UserManager {

    /**
     * 创建用户步骤接口。
     *
     * @author CH
     * @since 4.0.0
     */
    interface CreateUserStep {

    /**
     * 设置用户密码。
     *
     * @param password 密码
     * @return 当前步骤，便于链式调用
     */
        CreateUserStep withPassword(String password);

    /**
     * 设置允许登录的主机。
     *
     * @param host 主机
     * @return 当前步骤，便于链式调用
     */
        CreateUserStep withHost(String host);

    /**
     * 执行已配置的操作。
     */
        void execute();
    }

    /**
     * 修改用户步骤接口。
     *
     * @author CH
     * @since 4.0.0
     */
    interface AlterUserStep {

    /**
     * 设置用户密码。
     *
     * @param password 密码
     * @return 当前步骤，便于链式调用
     */
        AlterUserStep withPassword(String password);

    /**
     * 设置允许登录的主机。
     *
     * @param host 主机
     * @return 当前步骤，便于链式调用
     */
        AlterUserStep withHost(String host);

    /**
     * 追加授权项。
     *
     * @param privilege 权限名
     * @param database 数据库名
     * @return 当前步骤，便于链式调用
     */
        AlterUserStep withGrant(String privilege, String database);

    /**
     * 追加回收项。
     *
     * @param privilege 权限名
     * @param database 数据库名
     * @return 当前步骤，便于链式调用
     */
        AlterUserStep withRevoke(String privilege, String database);

    /**
     * 执行已配置的操作。
     */
        void execute();
    }

    /**
     * 删除用户步骤接口。
     *
     * @author CH
     * @since 4.0.0
     */
    interface DropUserStep {

    /**
     * 执行已配置的操作。
     */
        void execute();
    }

    /**
     * 返回 SPI 扩展键。
     *
     * @return 数据库类型标识
     */
    String type();

    /**
     * 查询所有用户。
     *
     * @return 用户信息列表
     */
    List<UserInfo> listUsers();

    /**
     * 创建用户。
     *
     * @param username 用户名
     * @return 创建用户步骤
     */
    CreateUserStep createUser(String username);

    /**
     * 删除用户。
     *
     * @param username 用户名
     * @return 删除用户步骤
     */
    DropUserStep dropUser(String username);

    /**
     * 修改用户。
     *
     * @param username 用户名
     * @return 修改用户步骤
     */
    AlterUserStep alterUser(String username);
}
