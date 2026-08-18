package com.chua.mysql.support.user;

import com.chua.datasource.support.user.UserManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlAlterUserStep implements UserManager.AlterUserStep {

    /** 数据来源 */
    private final DataSource dataSource;
    /** Username */
    private final String username;
    /** 密码 */
    private String password;
    /** 主机 */
    private String host;
    /** Grants */
    private final List<String> grants = new ArrayList<>();
    /** Revokes */
    private final List<String> revokes = new ArrayList<>();

    MysqlAlterUserStep(DataSource dataSource, String username) {
        this.dataSource = dataSource;
        this.username = username;
    }

    @Override
    public UserManager.AlterUserStep withPassword(String password) {
        this.password = password;
        return this;
    }

    @Override
    public UserManager.AlterUserStep withHost(String host) {
        this.host = host;
        return this;
    }

    @Override
    public UserManager.AlterUserStep withGrant(String privilege, String database) {
        String resolvedHost = host != null ? host : "%";
        grants.add("GRANT " + privilege + " ON " + database + " TO '" + username + "'@'" + resolvedHost + "'");
        return this;
    }

    @Override
    public UserManager.AlterUserStep withRevoke(String privilege, String database) {
        String resolvedHost = host != null ? host : "%";
        revokes.add("REVOKE " + privilege + " ON " + database + " FROM '" + username + "'@'" + resolvedHost + "'");
        return this;
    }

    @Override
    public void execute() {
        try (var c = dataSource.getConnection();
             var s = c.createStatement()) {
            if (password != null) {
                String resolvedHost = host != null ? host : "%";
                s.execute("ALTER USER '" + username + "'@'" + resolvedHost + "' IDENTIFIED BY '" + password + "'");
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
