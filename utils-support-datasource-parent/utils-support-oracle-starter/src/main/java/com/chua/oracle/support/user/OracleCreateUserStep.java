package com.chua.oracle.support.user;

import com.chua.datasource.support.user.UserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Oracle 创建用户链式步骤实现。
 * <p>
 * Oracle 语法：{@code CREATE USER 用户名 IDENTIFIED BY "密码"}，创建后自动授予 CONNECT 角色。
 * 用户名与权限目标都是裸标识符，必须命中白名单；口令以双引号包裹且禁止出现双引号与控制字符，
 * 防止闭合引号后改写语句。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OracleCreateUserStep implements UserManager.CreateUserStep {

    private static final Logger log = LoggerFactory.getLogger(OracleCreateUserStep.class);

    /**
     * 口令白名单：除控制字符与双引号外的任意字符，最长 128
     */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("[^\\p{Cntrl}\"]{1,128}");

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
     * 主机（Oracle 无此概念）
     */
    private String host = "";

    /**
     * 构造方法，创建 Oracle 创建用户步骤实例。
     *
     * @param dataSource 数据来源，不允许为 null
     * @param username   用户名，必须命中用户名白名单
     */
    OracleCreateUserStep(DataSource dataSource, String username) {
        this.dataSource = dataSource;
        this.username = OracleSqlNames.checkUserName(username);
    }

    /**
     * 设置用户密码。
     *
     * @param password 明文密码
     * @return this
     */
    @Override
    public UserManager.CreateUserStep withPassword(String password) {
        this.password = checkPassword(password);
        return this;
    }

    /**
     * Oracle 不支持主机概念，忽略此参数。
     *
     * @param host 忽略
     * @return this
     */
    @Override
    public UserManager.CreateUserStep withHost(String host) {
        this.host = host;
        return this;
    }

    /**
     * 执行创建用户并授予 CONNECT 角色。
     * <p>
     * 两条语句逐条下发，Oracle 的 DDL 无法回滚：若第二条失败，第一条已生效，
     * 用 warn 记录已执行条数，并把失败语句的序号与身份（不含口令）放进异常消息。
     * </p>
     */
    @Override
    public void execute() {
        String user = OracleSqlNames.checkUserName(username);
        if (password == null) {
            throw new IllegalStateException("创建用户失败：未指定口令 " + user);
        }
        List<SqlStep> steps = new ArrayList<>();
        steps.add(new SqlStep("CREATE USER " + user + " 指定口令",
                "CREATE USER " + user + " IDENTIFIED BY \"" + checkPassword(password) + "\""));
        steps.add(new SqlStep("GRANT CONNECT TO " + user, "GRANT CONNECT TO " + user));
        int done = 0;
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            for (SqlStep step : steps) {
                try {
                    s.execute(step.sql());
                } catch (SQLException e) {
                    log.warn("Oracle 创建用户未完成，第 {}/{} 条语句 [{}] 执行失败，此前已执行 {} 条，DDL 不可回滚",
                            done + 1, steps.size(), step.identity(), done);
                    throw new IllegalStateException("创建用户失败：第 " + (done + 1) + "/" + steps.size()
                            + " 条语句执行失败 [" + step.identity() + "]，此前已执行 " + done + " 条", e);
                }
                done++;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建用户失败: " + user, e);
        }
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
     * 一条待执行语句及其可回显身份。
     *
     * @param identity 语句身份，不含口令
     * @param sql      待执行语句
     */
    private record SqlStep(String identity, String sql) {
    }
}
