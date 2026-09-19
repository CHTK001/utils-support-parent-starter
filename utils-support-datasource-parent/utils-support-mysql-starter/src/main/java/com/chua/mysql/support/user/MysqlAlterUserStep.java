package com.chua.mysql.support.user;

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
 * MySQL 修改用户链式步骤实现。
 * <p>
 * 支持改口令、授权与回收权限，最终按“改口令 - 授权 - 回收”的顺序逐条执行。
 * 所有来自调用方的名字、主机、权限名、授权目标都要先过白名单校验，口令以字面量形式转义后下发，
 * 避免通过字符串拼接改写 GRANT / REVOKE / ALTER USER 语句。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlAlterUserStep implements UserManager.AlterUserStep {

    private static final Logger log = LoggerFactory.getLogger(MysqlAlterUserStep.class);

    /**
     * 主机白名单：允许 {@code %} 通配、主机名、IPv4，以及 IPv6 网段所需的 {@code : %}
     */
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:%-]+");
    /**
     * 单个权限白名单：权限名 + 可选列清单
     */
    private static final Pattern PRIVILEGE_PATTERN = Pattern.compile("[A-Za-z_]+(\\([A-Za-z_]+(\\s*,\\s*[A-Za-z_]+)*\\))?");
    /**
     * {@code ALL} 与 {@code ALL PRIVILEGES} 的整体写法
     */
    private static final Pattern ALL_PRIVILEGES_PATTERN = Pattern.compile("ALL(\\s+PRIVILEGES)?", Pattern.CASE_INSENSITIVE);
    /**
     * 授权目标白名单：{@code *}、库名、{@code 库.*}、{@code 库.表}
     */
    private static final Pattern GRANT_TARGET_PATTERN = Pattern.compile("\\*|[A-Za-z0-9_$]+(\\.([A-Za-z0-9_$]+|\\*))?");
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
    private String host;
    /**
     * 待执行的授权语句
     */
    private final List<SqlStep> grants = new ArrayList<>();
    /**
     * 待执行的回收语句
     */
    private final List<SqlStep> revokes = new ArrayList<>();

    /**
     * 构造方法，创建修改用户步骤实例。
     *
     * @param dataSource 数据来源，不允许为 null
     * @param username   用户名，必须命中用户名白名单
     */
    MysqlAlterUserStep(DataSource dataSource, String username) {
        this.dataSource = dataSource;
        this.username = MysqlSqlNames.checkUserName(username);
    }

    /**
     * 设置新口令。
     *
     * @param password 新口令，为 null 表示不改口令
     * @return this
     */
    @Override
    public UserManager.AlterUserStep withPassword(String password) {
        this.password = password == null ? null : checkPassword(password);
        return this;
    }

    /**
     * 设置账号所属主机。
     *
     * @param host 主机，必须命中主机白名单
     * @return this
     */
    @Override
    public UserManager.AlterUserStep withHost(String host) {
        this.host = checkHost(host);
        return this;
    }

    /**
     * 追加一条授权语句。
     *
     * @param privilege 权限列表，只接受权限名与可选列清单，{@code *} 作为权限名会被拒绝
     * @param database  授权目标，支持 {@code *}、库名、{@code 库.*}、{@code 库.表}
     * @return this
     */
    @Override
    public UserManager.AlterUserStep withGrant(String privilege, String database) {
        String sql = "GRANT " + checkPrivileges(privilege) + " ON " + checkGrantTarget(database)
                + " TO " + accountLiteral();
        grants.add(new SqlStep(sql, sql));
        return this;
    }

    /**
     * 追加一条回收语句。
     *
     * @param privilege 权限列表，校验规则同 {@link #withGrant(String, String)}
     * @param database  回收目标，校验规则同 {@link #withGrant(String, String)}
     * @return this
     */
    @Override
    public UserManager.AlterUserStep withRevoke(String privilege, String database) {
        String sql = "REVOKE " + checkPrivileges(privilege) + " ON " + checkGrantTarget(database)
                + " FROM " + accountLiteral();
        revokes.add(new SqlStep(sql, sql));
        return this;
    }

    /**
     * 依次执行改口令、授权、回收。
     * <p>
     * MySQL 的 DDL 无法回滚，因此逐条下发；中途失败时用 warn 记录已执行条数，
     * 并把失败语句的序号与身份（不含口令）放进异常消息。
     * </p>
     */
    @Override
    public void execute() {
        String account = accountLiteral();
        List<SqlStep> steps = new ArrayList<>();
        if (password != null) {
            steps.add(new SqlStep("ALTER USER " + account + " 改口令",
                    "ALTER USER " + account + " IDENTIFIED BY " + quoteLiteral(checkPassword(password))));
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
                    log.warn("MySQL 修改用户未完成，第 {}/{} 条语句 [{}] 执行失败，此前已执行 {} 条，DDL 不可回滚",
                            done + 1, steps.size(), step.identity(), done);
                    throw new IllegalStateException("修改用户失败：第 " + (done + 1) + "/" + steps.size()
                            + " 条语句执行失败 [" + step.identity() + "]，此前已执行 " + done + " 条", e);
                }
                done++;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("修改用户失败: " + account, e);
        }
    }

    /**
     * 拼装 {@code '用户名'@'主机'} 形式的账号字面量。
     *
     * @return 已转义的账号字面量
     */
    private String accountLiteral() {
        return quoteLiteral(MysqlSqlNames.checkUserName(username)) + "@" + quoteLiteral(checkHost(host == null ? "%" : host));
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
     * 校验权限列表白名单，按顶层逗号切分后逐项匹配“权限名 + 可选列清单”。
     * <p>
     * {@code *} 不是合法权限名，因此 {@code "SELECT ON *.* TO x"} 之类的改写会被整体拒绝。
     * </p>
     *
     * @param privileges 权限列表
     * @return 规范化后的权限列表
     */
    private static String checkPrivileges(String privileges) {
        if (privileges == null || privileges.trim().isEmpty()) {
            throw new IllegalArgumentException("权限列表不能为空");
        }
        List<String> items = splitTopLevel(privileges);
        List<String> checked = new ArrayList<>(items.size());
        for (String item : items) {
            String privilege = item.trim();
            if (privilege.isEmpty()
                    || (!PRIVILEGE_PATTERN.matcher(privilege).matches()
                    && !ALL_PRIVILEGES_PATTERN.matcher(privilege).matches())) {
                throw new IllegalArgumentException("非法权限名: " + privilege);
            }
            checked.add(privilege);
        }
        return String.join(", ", checked);
    }

    /**
     * 校验授权目标白名单。
     *
     * @param database 授权目标
     * @return 校验通过的授权目标
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
     * 按顶层逗号切分权限列表，括号内的逗号不参与切分。
     *
     * @param privileges 权限列表
     * @return 切分后的权限项
     */
    private static List<String> splitTopLevel(String privileges) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < privileges.length(); i++) {
            char ch = privileges.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (ch == ',' && depth == 0) {
                items.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        items.add(current.toString());
        return items;
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

    /**
     * 一条待执行语句及其可回显身份。
     *
     * @param identity 语句身份，不含口令
     * @param sql      待执行语句
     */
    private record SqlStep(String identity, String sql) {
    }
}
