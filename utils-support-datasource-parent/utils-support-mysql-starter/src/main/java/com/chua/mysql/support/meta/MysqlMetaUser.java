package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.meta.MetaUser;
import com.chua.common.support.lang.datasource.meta.UserAlterBuilder;
import com.chua.common.support.lang.datasource.meta.UserCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.UserDef;
import com.chua.mysql.support.user.MysqlSqlNames;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MySQL 用户元数据操作实现。
 * <p>
 * 账号名、主机走白名单校验，口令走白名单校验后再转义成字面量；
 * 异常消息与日志只描述语句身份，不回显口令。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlMetaUser implements MetaUser {

    /**
     * 主机白名单：允许 {@code %} 通配、主机名、IPv4，以及 IPv6 网段所需的 {@code : %}
     */
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:%-]+");
    /**
     * 口令白名单：除控制字符与单引号外的任意字符，最长 128
     */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("[^\\p{Cntrl}']{1,128}");

    /**
     * 数据源
     */
    private final DataSource dataSource;

    /**
     * 构造方法，创建 MySQL 用户元数据操作实例。
     *
     * @param dataSource 数据源
     */
    public MysqlMetaUser(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 列出所有账号。
     *
     * @return 用户定义列表
     */
    @Override
    public List<UserDef> list() {
        List<UserDef> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT user, host, account_locked, password_last_changed"
                             + " FROM mysql.user")) {
            while (rs.next()) {
                result.add(UserDef.builder()
                        .user(rs.getString("user"))
                        .host(rs.getString("host"))
                        .locked("Y".equals(rs.getString("account_locked")))
                        .passwordLastChanged(rs.getString("password_last_changed"))
                        .build());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("列出 MySQL 用户失败", e);
        }
        return result;
    }

    /**
     * 构造创建账号步骤。
     *
     * @param username 用户名，必须命中用户名白名单
     * @return 创建账号步骤
     */
    @Override
    public UserCreateBuilder create(String username) {
        return new CreateStep(dataSource, username);
    }

    /**
     * 构造修改账号步骤。
     *
     * @param username 用户名，必须命中用户名白名单
     * @return 修改账号步骤
     */
    @Override
    public UserAlterBuilder alter(String username) {
        return new AlterStep(dataSource, username);
    }

    /**
     * 删除账号（仅删除 {@code '%'} 主机的账号）。
     *
     * @param username 用户名，必须命中用户名白名单
     * @return 执行成功返回 true
     */
    @Override
    public boolean drop(String username) {
        String account = quoteLiteral(MysqlSqlNames.checkUserName(username)) + "@'%'";
        exec(dataSource, "DROP USER IF EXISTS " + account, "删除 MySQL 用户 " + account);
        return true;
    }

    /**
     * 执行一条用户语句。
     *
     * @param ds       数据源
     * @param sql      待执行语句
     * @param identity 语句身份，用于异常消息，必须不含口令
     */
    private static void exec(DataSource ds, String sql, String identity) {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(identity + " 执行失败", e);
        }
    }

    /**
     * 校验主机白名单。
     *
     * @param host 主机
     * @return 校验通过的主机
     */
    private static String checkHost(String host) {
        if (host == null || !HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("非法主机: " + host);
        }
        return host;
    }

    /**
     * 校验口令白名单，失败时不回显口令内容。
     *
     * @param password 口令
     * @return 校验通过的口令
     */
    private static String checkPassword(String password) {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new IllegalArgumentException("口令包含非法字符");
        }
        return password;
    }

    /**
     * 生成单引号字面量：反斜杠加倍，单引号成对。
     * <p>
     * 反斜杠加倍在默认模式与 {@code NO_BACKSLASH_ESCAPES} 下都是安全的。
     * </p>
     *
     * @param value 原始值
     * @return 含首尾单引号的字面量
     */
    private static String quoteLiteral(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    // ==================== Inner Steps ====================

    /**
     * 创建账号步骤实现。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class CreateStep implements UserCreateBuilder {

        /**
         * 数据源
         */
        private final DataSource dataSource;
        /**
         * 用户名
         */
        private final String username;
        /**
         * 口令
         */
        private String password = "123456";
        /**
         * 主机
         */
        private String host = "%";

        CreateStep(DataSource dataSource, String username) {
            this.dataSource = dataSource;
            this.username = MysqlSqlNames.checkUserName(username);
        }

        /**
         * 指定口令。
         *
         * @param password 明文口令
         * @return this
         */
        @Override
        public UserCreateBuilder withPassword(String password) {
            this.password = checkPassword(password);
            return this;
        }

        /**
         * 指定主机。
         *
         * @param host 主机，必须命中主机白名单
         * @return this
         */
        @Override
        public UserCreateBuilder withHost(String host) {
            this.host = checkHost(host);
            return this;
        }

        /**
         * 执行创建账号。
         *
         * @return 执行成功返回 true
         */
        @Override
        public boolean execute() {
            String account = quoteLiteral(MysqlSqlNames.checkUserName(username)) + "@" + quoteLiteral(checkHost(host));
            String sql = "CREATE USER " + account + " IDENTIFIED BY " + quoteLiteral(checkPassword(password));
            exec(dataSource, sql, "创建 MySQL 用户 " + account);
            return true;
        }
    }

    /**
     * 修改账号步骤实现。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class AlterStep implements UserAlterBuilder {

        /**
         * 数据源
         */
        private final DataSource dataSource;
        /**
         * 用户名
         */
        private final String username;
        /**
         * 口令
         */
        private String password = null;

        AlterStep(DataSource dataSource, String username) {
            this.dataSource = dataSource;
            this.username = MysqlSqlNames.checkUserName(username);
        }

        /**
         * 指定新口令。
         *
         * @param password 明文口令
         * @return this
         */
        @Override
        public UserAlterBuilder withPassword(String password) {
            this.password = password == null ? null : checkPassword(password);
            return this;
        }

        /**
         * MySQL 的改口令语句不携带主机，忽略该参数。
         *
         * @param host 主机
         * @return this
         */
        @Override
        public UserAlterBuilder withHost(String host) {
            return this;
        }

        /**
         * 执行修改账号口令。
         *
         * @return 执行成功返回 true
         */
        @Override
        public boolean execute() {
            String account = quoteLiteral(MysqlSqlNames.checkUserName(username)) + "@'%'";
            StringBuilder sql = new StringBuilder("ALTER USER ").append(account);
            if (password != null) {
                sql.append(" IDENTIFIED BY ").append(quoteLiteral(checkPassword(password)));
            }
            exec(dataSource, sql.toString(), "修改 MySQL 用户 " + account);
            return true;
        }
    }
}
