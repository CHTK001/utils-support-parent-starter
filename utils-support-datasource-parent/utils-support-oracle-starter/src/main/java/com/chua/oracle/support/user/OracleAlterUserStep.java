package com.chua.oracle.support.user;

import com.chua.datasource.support.user.UserManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
* Oracle 修改用户链式步骤实现。
* <p>
* 支持修改密码、授予权限和回收权限：
* <ul>
*   <li>{@code ALTER USER 用户名 IDENTIFIED BY "新密码"}</li>
*   <li>{@code GRANT 权限 TO 用户名}</li>
*   <li>{@code REVOKE 权限 FROM 用户名}</li>
* </ul>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class OracleAlterUserStep implements UserManager.AlterUserStep {

    /** 数据来源 */
    private final DataSource dataSource;
    /** 用户名 */
    private final String username;
    /** 密码 */
    private String password;
    /** Grants */
    private final List<String> grants = new ArrayList<>();
    /** Revokes */
    private final List<String> revokes = new ArrayList<>();

    /**
     * 构造方法，创建 OracleAlter用户Step 实例。
     *
     * @param dataSource 数据来源，不允许为 null
     * @param username 用户名，不允许为 null
     */
    OracleAlterUserStep(DataSource dataSource, String username) {
        this.dataSource = dataSource;
        this.username = username;
    }

    /**
    * 设置新密码。
    *
    * @param password 新密码
    * @return this
    */
    @Override
    public UserManager.AlterUserStep withPassword(String password) {
        this.password = password;
        return this;
    }

    /**
    * Oracle 不支持 主机 概念，忽略此参数。
    *
    * @param host 忽略
    * @return this
    */
    @Override
    public UserManager.AlterUserStep withHost(String host) {
        return this;
    }

    /**
    * 授予权限。
    *
    * @param privilege 权限名（如 创建 会话、选择 任意 TABLE）
    * @param database  忽略（Oracle 中直接对用户授权）
    * @return this
    */
    @Override
    public UserManager.AlterUserStep withGrant(String privilege, String database) {
        grants.add("GRANT " + privilege + " TO " + username);
        return this;
    }

    /**
    * 回收权限。
    *
    * @param privilege 权限名
    * @param database  忽略
    * @return this
    */
    @Override
    public UserManager.AlterUserStep withRevoke(String privilege, String database) {
        revokes.add("REVOKE " + privilege + " FROM " + username);
        return this;
    }

    /**
    * 依次执行修改密码（如有）、授权、回收操作。
    */
    @Override
    public void execute() {
        try (var c = dataSource.getConnection();
             var s = c.createStatement()) {
            if (password != null) {
                s.execute("ALTER USER " + username + " IDENTIFIED BY \"" + password + "\"");
            }
            for (String g : grants) {
                s.execute(g);
            }
            for (String r : revokes) {
                s.execute(r);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
