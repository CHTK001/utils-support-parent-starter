package com.chua.datasource.support.user;

import java.util.List;

/**
 * 用户管理器 SPI 接口。
 *
 * @author CH
 */
public interface UserManager {

    /**
     * 创建用户步骤接口。
     *
 * @author CH
     */
    interface CreateUserStep {

        CreateUserStep withPassword(String password);

        CreateUserStep withHost(String host);

        void execute();
    }

    /**
     * 修改用户步骤接口。
     *
 * @author CH
     */
    interface AlterUserStep {

        AlterUserStep withPassword(String password);

        AlterUserStep withHost(String host);

        AlterUserStep withGrant(String privilege, String database);

        AlterUserStep withRevoke(String privilege, String database);

        void execute();
    }

    /**
     * 删除用户步骤接口。
     *
 * @author CH
     */
    interface DropUserStep {

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
