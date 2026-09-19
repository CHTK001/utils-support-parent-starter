package com.chua.mysql.support.user;

import com.chua.datasource.support.user.UserManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * MySQL 创建用户链式步骤实现。
 * <p>
 * 用户名、主机、口令都来自调用方：前两者走白名单校验，口令走白名单校验后再以字面量形式转义下发，
 * 避免拼接改写 {@code CREATE USER} 语句。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlCreateUserStep implements UserManager.CreateUserStep {

    /**
     * 主机白名单：允许 {@code %} 通配、主机名、IPv4，以及 IPv6 网段所需的 {@code : %}
     */
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:%-]+");
    /**
     * 口令白名单：除控制字符与单引号外的任意字符，最长 128
     */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("[^\\p{Cntrl}']{1,128}");

    /**
     * 数据来源
     */
    private final DataSource dataSource;
    /**
     * 用户名
     */
    private final String username;
    /**
     * 密码
     */
    private String password;
    /**
     * 主机
     */
    private String host = "%";

    /**
     * 构造方法，创建 MySQL 创建用户步骤实例。
     *
     * @param dataSource 数据来源，不允许为 null
     * @param username   用户名，必须命中用户名白名单
     */
    MysqlCreateUserStep(DataSource dataSource, String username) {
        this.dataSource = dataSource;
        this.username = MysqlSqlNames.checkUserName(username);
    }

    /**
     * 设置用户口令。
     *
     * @param password 明文口令
     * @return this
     */
    @Override
    public UserManager.CreateUserStep withPassword(String password) {
        this.password = checkPassword(password);
        return this;
    }

    /**
     * 设置账号所属主机。
     *
     * @param host 主机，必须命中主机白名单
     * @return this
     */
    @Override
    public UserManager.CreateUserStep withHost(String host) {
        this.host = checkHost(host);
        return this;
    }

    /**
     * 执行创建用户。
     */
    @Override
    public void execute() {
        String account = quoteLiteral(MysqlSqlNames.checkUserName(username)) + "@" + quoteLiteral(checkHost(host));
        if (password == null) {
            throw new IllegalStateException("创建用户失败：未指定口令 " + account);
        }
        String sql = "CREATE USER IF NOT EXISTS " + account + " IDENTIFIED BY "
                + quoteLiteral(checkPassword(password));
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("创建用户失败: " + account, e);
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
        if (password != null && !PASSWORD_PATTERN.matcher(password).matches()) {
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
}
