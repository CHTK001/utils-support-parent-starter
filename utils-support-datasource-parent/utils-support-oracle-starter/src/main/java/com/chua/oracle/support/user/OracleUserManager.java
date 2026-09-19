package com.chua.oracle.support.user;

import com.chua.datasource.support.user.DataSourceAware;
import com.chua.datasource.support.user.UserInfo;
import com.chua.datasource.support.user.UserManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle 用户管理器 SPI 实现。
 * <p>
 * Oracle 的用户管理语法与 MySQL 不同：
 * <ul>
 *   <li>用户即 Schema（一个用户对应一个同名 Schema）</li>
 *   <li>没有 MySQL 的 host 概念（@'%' 等）</li>
 *   <li>创建用户使用 CREATE USER，自动授予 CONNECT 角色</li>
 *   <li>删除用户需使用 CASCADE 以级联删除其 Schema 对象</li>
 *   <li>用户列表查询 dba_users 视图需要 DBA 权限</li>
 * </ul>
 * 用户名在语句中以裸标识符出现，因此一律先过白名单校验，杜绝拼接注入。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OracleUserManager implements UserManager, DataSourceAware {

    /**
     * 数据来源
     */
    private DataSource dataSource;

    /**
     * 返回 SPI 扩展键：{@code oracle}
     *
     * @return "oracle"
     */
    @Override
    public String type() {
        return "oracle";
    }

    /**
     * 设置 JDBC 数据源，由 SPI 工厂自动调用。
     *
     * @param dataSource 数据源
     */
    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 查询 Oracle 数据库中所有用户（需要 DBA 权限访问 dba_users 视图）。
     *
     * @return 用户信息列表
     */
    @Override
    public List<UserInfo> listUsers() {
        List<UserInfo> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT username FROM dba_users ORDER BY username")) {
            while (rs.next()) {
                UserInfo ui = new UserInfo();
                ui.setUser(rs.getString("username"));
                ui.setHost("");
                list.add(ui);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("列出 Oracle 用户失败", e);
        }
        return list;
    }

    /**
     * 创建一个 Oracle 用户的链式构建器。
     *
     * @param username 用户名，必须命中用户名白名单
     * @return 创建用户的链式步骤对象
     */
    @Override
    public CreateUserStep createUser(String username) {
        return new OracleCreateUserStep(dataSource, username);
    }

    /**
     * 删除一个 Oracle 用户（使用 CASCADE 级联删除其 Schema 对象）。
     *
     * @param username 用户名，必须命中用户名白名单
     * @return 执行步骤对象
     */
    @Override
    public DropUserStep dropUser(String username) {
        String user = OracleSqlNames.checkUserName(username);
        return () -> {
            String sql = "DROP USER " + user + " CASCADE";
            try (Connection c = dataSource.getConnection();
                 Statement s = c.createStatement()) {
                s.execute(sql);
            } catch (SQLException e) {
                throw new IllegalStateException("删除用户失败: " + user, e);
            }
        };
    }

    /**
     * 修改 Oracle 用户属性的链式构建器。
     *
     * @param username 用户名，必须命中用户名白名单
     * @return 修改用户的链式步骤对象
     */
    @Override
    public AlterUserStep alterUser(String username) {
        return new OracleAlterUserStep(dataSource, username);
    }

}
