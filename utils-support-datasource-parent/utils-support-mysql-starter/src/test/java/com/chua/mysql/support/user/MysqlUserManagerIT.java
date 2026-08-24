package com.chua.mysql.support.user;

import com.chua.datasource.support.user.UserInfo;
import com.chua.datasource.support.user.UserManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.logging.Logger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MysqlUserManager 生产实现真实容器测试。
 */
class MysqlUserManagerIT {

    private static final String HOST = "172.16.0.40";
    private static final String URL = "jdbc:mysql://" + HOST + ":3306/report?useSSL=false&allowPublicKeyRetrieval=true";

    @BeforeAll
    static void assumeReachable() {
        Assumptions.assumeTrue(reachable(HOST, 3306), "MySQL 不可达，跳过");
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static DataSource mysqlDs() {
        return new DataSource() {
            @Override public Connection getConnection() throws Exception {
                return java.sql.DriverManager.getConnection(URL, "root", "root@");
            }
            @Override public Connection getConnection(String u, String p) throws Exception { return getConnection(); }
            @Override public <T> T unwrap(Class<T> c) { return null; }
            @Override public boolean isWrapperFor(Class<?> c) { return false; }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter out) {}
            @Override public void setLoginTimeout(int s) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() { return Logger.getLogger("mysql"); }
        };
    }

    @Test
    void listUsers_nonEmpty() {
        UserManager m = newUserManager(mysqlDs());
        List<UserInfo> users = m.listUsers();
        assertFalse(users.isEmpty(), "root@% 应存在");
    }

    @Test
    void userLifecycle_createListDrop() {
        UserManager m = newUserManager(mysqlDs());
        String name = "it_u_" + System.nanoTime();

        m.createUser(name).withPassword("Passw0rd!").withHost("%").execute();
        assertTrue(m.listUsers().stream()
                .anyMatch(u -> u.getUser().equals(name)), "创建后应可见");

        m.alterUser(name).withPassword("New12345!").execute();
        m.dropUser(name).execute();
        assertFalse(m.listUsers().stream()
                .anyMatch(u -> u.getUser().equals(name)), "删除后不应存在");
    }

    private UserManager newUserManager(DataSource ds) {
        MysqlUserManager m = new MysqlUserManager();
        m.setDataSource(ds);
        return m;
    }
}
