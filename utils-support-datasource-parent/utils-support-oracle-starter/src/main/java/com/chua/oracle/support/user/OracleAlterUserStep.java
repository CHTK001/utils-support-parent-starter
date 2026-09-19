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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Oracle 修改用户链式步骤实现。
 * <p>
 * 支持修改密码、授予权限和回收权限：
 * <ul>
 *   <li>{@code ALTER USER 用户名 IDENTIFIED BY "新密码"}</li>
 *   <li>{@code GRANT 权限 [ON 目标] TO 用户名}</li>
 *   <li>{@code REVOKE 权限 [ON 目标] FROM 用户名}</li>
 * </ul>
 * Oracle 的用户名在语句中以裸标识符出现，因此必须命中标识符白名单；
 * 权限名允许由标识符组成的词组（如 {@code CREATE SESSION}），但拒绝子句关键字，
 * 防止把额外的子句拼进 GRANT / REVOKE 文本。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OracleAlterUserStep implements UserManager.AlterUserStep {

    private static final Logger log = LoggerFactory.getLogger(OracleAlterUserStep.class);

    /**
     * 权限白名单：由标识符组成的词组，覆盖系统权限与角色名
     */
    private static final Pattern PRIVILEGE_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*(\\s+[A-Za-z][A-Za-z0-9_$#]*)*");
    /**
     * 授权目标白名单：表/视图等对象名（Oracle 无 {@code 库.*} 概念）
     */
    private static final Pattern GRANT_TARGET_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]{0,63}");
    /**
     * 口令白名单：除控制字符与双引号外的任意字符，最长 128
     */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("[^\\p{Cntrl}\"]{1,128}");
    /**
     * 权限名中不允许出现的子句关键字，避免改写 GRANT / REVOKE 语义
     */
    private static final Set<String> RESERVED_WORDS = Set.of(
            "TO", "FROM", "WITH", "ADMIN", "IDENTIFIED", "GRANT", "REVOKE", "USER", "ON", "BY",
            "CASCADE", "IF", "EXISTS", "AND", "OR", "NOT");

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
     * 待执行的授权语句
     */
    private final List<SqlStep> grants = new ArrayList<>();
    /**
     * 待执行的回收语句
     */
    private final List<SqlStep> revokes = new ArrayList<>();

    /**
     * 构造方法，创建 Oracle 修改用户步骤实例。
     *
     * @param dataSource 数据来源，不允许为 null
     * @param username   用户名，必须命中用户名白名单
     */
    OracleAlterUserStep(DataSource dataSource, String username) {
        this.dataSource = dataSource;
        this.username = OracleSqlNames.checkUserName(username);
    }

    /**
     * 设置新密码。
     *
     * @param password 新密码，为 null 表示不改密码
     * @return this
     */
    @Override
    public UserManager.AlterUserStep withPassword(String password) {
        this.password = password == null ? null : checkPassword(password);
        return this;
    }

    /**
     * Oracle 不支持主机概念，忽略此参数。
     *
     * @param host 忽略
     * @return this
     */
    @Override
    public UserManager.AlterUserStep withHost(String host) {
        return this;
    }

    /**
     * 追加一条授权语句。
     *
     * @param privilege 权限名或系统权限词组，如 {@code CREATE SESSION}
     * @param database  对象名，仅允许裸标识符；为空表示系统权限或角色
     * @return this
     */
    @Override
    public UserManager.AlterUserStep withGrant(String privilege, String database) {
        String sql = "GRANT " + checkPrivileges(privilege) + objectClause(database)
                + " TO " + OracleSqlNames.checkUserName(username);
        grants.add(new SqlStep(sql, sql));
        return this;
    }

    /**
     * 追加一条回收语句。
     *
     * @param privilege 权限名或系统权限词组
     * @param database  对象名，校验规则同 {@link #withGrant(String, String)}
     * @return this
     */
    @Override
    public UserManager.AlterUserStep withRevoke(String privilege, String database) {
        String sql = "REVOKE " + checkPrivileges(privilege) + objectClause(database)
                + " FROM " + OracleSqlNames.checkUserName(username);
        revokes.add(new SqlStep(sql, sql));
        return this;
    }

    /**
     * 依次执行改密码（如有）、授权、回收。
     * <p>
     * Oracle 的 DDL 无法回滚，中途失败时用 warn 记录已执行条数，
     * 并把失败语句的序号与身份（不含口令）放进异常消息。
     * </p>
     */
    @Override
    public void execute() {
        String user = OracleSqlNames.checkUserName(username);
        List<SqlStep> steps = new ArrayList<>();
        if (password != null) {
            steps.add(new SqlStep("ALTER USER " + user + " 改口令",
                    "ALTER USER " + user + " IDENTIFIED BY \"" + checkPassword(password) + "\""));
        }
        steps.addAll(grants);
        steps.addAll(revokes);
        int done = 0;
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            for (SqlStep step : steps) {
                try {
                    s.execute(step.sql());
                } catch (SQLException e) {
                    log.warn("Oracle 修改用户未完成，第 {}/{} 条语句 [{}] 执行失败，此前已执行 {} 条，DDL 不可回滚",
                            done + 1, steps.size(), step.identity(), done);
                    throw new IllegalStateException("修改用户失败：第 " + (done + 1) + "/" + steps.size()
                            + " 条语句执行失败 [" + step.identity() + "]，此前已执行 " + done + " 条", e);
                }
                done++;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("修改用户失败: " + user, e);
        }
    }

    /**
     * 拼装对象权限的 {@code ON} 子句。
     *
     * @param database 对象名，可为空
     * @return {@code ON 对象名} 或空串
     */
    private static String objectClause(String database) {
        if (database == null || database.trim().isEmpty()) {
            return "";
        }
        return " ON " + checkGrantTarget(database);
    }

    /**
     * 校验权限白名单：允许 {@code ALL} 与由标识符组成的词组，逐项排除子句关键字。
     *
     * @param privileges 权限列表，逗号分隔
     * @return 规范化后的权限列表
     */
    private static String checkPrivileges(String privileges) {
        if (privileges == null || privileges.trim().isEmpty()) {
            throw new IllegalArgumentException("权限列表不能为空");
        }
        List<String> checked = new ArrayList<>();
        for (String item : privileges.split(",")) {
            String privilege = item.trim().replaceAll("\\s+", " ");
            if (privilege.isEmpty() || !PRIVILEGE_PATTERN.matcher(privilege).matches()) {
                throw new IllegalArgumentException("非法权限名: " + privilege);
            }
            for (String word : privilege.split(" ")) {
                if (RESERVED_WORDS.contains(word.toUpperCase())) {
                    throw new IllegalArgumentException("非法权限名: " + privilege);
                }
            }
            checked.add(privilege);
        }
        return String.join(", ", checked);
    }

    /**
     * 校验授权目标白名单。
     *
     * @param database 对象名
     * @return 校验通过的对象名
     */
    private static String checkGrantTarget(String database) {
        String target = database == null ? "" : database.trim();
        if (!GRANT_TARGET_PATTERN.matcher(target).matches()) {
            throw new IllegalArgumentException("非法授权目标: " + database);
        }
        return target;
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
