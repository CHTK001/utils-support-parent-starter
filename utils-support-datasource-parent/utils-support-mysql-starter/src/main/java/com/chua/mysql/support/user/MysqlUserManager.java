package com.chua.mysql.support.user;

import com.chua.datasource.support.user.DataSourceAware;
import com.chua.datasource.support.user.UserInfo;
import com.chua.datasource.support.user.UserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 用户管理器 SPI 实现。
 * <p>
 * 下发的 DDL 中所有账号名都先过白名单校验再转义成字面量，避免拼接注入。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlUserManager implements UserManager, DataSourceAware {

    private static final Logger log = LoggerFactory.getLogger(MysqlUserManager.class);

    /**
     * 数据来源
     */
    private DataSource dataSource;

    /**
     * 返回 SPI 扩展键：{@code mysql}
     *
     * @return "mysql"
     */
    @Override
    public String type() {
        return "mysql";
    }

    /**
     * 设置 JDBC 数据源。
     *
     * @param dataSource 数据源
     */
    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 查询所有账号，包含口令摘要列（无权限时跳过该列）。
     *
     * @return 用户信息列表
     */
    @Override
    public List<UserInfo> listUsers() {
        List<UserInfo> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT user, host, authentication_string FROM mysql.user")) {
            while (rs.next()) {
                UserInfo ui = new UserInfo();
                ui.setUser(rs.getString("user"));
                ui.setHost(rs.getString("host"));
                try {
                    ui.setPassword(rs.getString("authentication_string"));
                } catch (SQLException ignored) {
                    log.debug("跳过 authentication_string 列：当前账号无权读取该列");
                }
                list.add(ui);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("列出 MySQL 用户失败", e);
        }
        return list;
    }

    /**
     * 创建用户的链式步骤。
     *
     * @param username 用户名，必须命中用户名白名单
     * @return 创建用户步骤
     */
    @Override
    public CreateUserStep createUser(String username) {
        return new MysqlCreateUserStep(dataSource, username);
    }

    /**
     * 删除用户的链式步骤，仅删除 {@code '%'} 主机的账号。
     *
     * @param username 用户名，必须命中用户名白名单
     * @return 删除用户步骤
     */
    @Override
    public DropUserStep dropUser(String username) {
        String account = quoteLiteral(MysqlSqlNames.checkUserName(username)) + "@'%'";
        return () -> {
            String sql = "DROP USER IF EXISTS " + account;
            try (Connection c = dataSource.getConnection();
                 Statement s = c.createStatement()) {
                s.execute(sql);
            } catch (SQLException e) {
                throw new IllegalStateException("删除用户失败: " + account, e);
            }
        };
    }

    /**
     * 修改用户的链式步骤。
     *
     * @param username 用户名，必须命中用户名白名单
     * @return 修改用户步骤
     */
    @Override
    public AlterUserStep alterUser(String username) {
        return new MysqlAlterUserStep(dataSource, username);
    }

    /**
     * 生成单引号字面量：反斜杠加倍，单引号成对。
     *
     * @param value 原始值
     * @return 含首尾单引号的字面量
     */
    private static String quoteLiteral(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }
}
