package com.chua.mysql.support.engine;

import com.chua.datasource.support.user.UserInfo;
import org.junit.jupiter.api.*;

import java.util.List;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcEngineTraceTest {

    private static final String HOST = "172.16.0.40";
    private static final int PORT = 3308;
    private static final String ADMIN_USER = "root";
    private static final String ADMIN_PASS = "root";
    private static final String TEST_DB = "jdbc_engine_test_db";
    private static final String TEST_USER = "engine_test_user";

    @BeforeAll
    static void assumeEnv() {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(HOST, PORT), 5000);
            s.close();
        } catch (Exception e) {
            throw new SkippedException("MySQL unreachable");
        }
    }

    @BeforeEach
    void setUp() {
        MysqlEngine engine = new MysqlEngine();
        engine.addDataSource("admin", HOST, PORT, "mysql", ADMIN_USER, ADMIN_PASS);
        engine.setDefaultDataSourceName("admin");
        // clean all hosts
        try {
            java.sql.Connection conn = java.sql.DriverManager.getConnection(
                    "jdbc:mysql://" + HOST + ":" + PORT + "/mysql?useSSL=false&allowPublicKeyRetrieval=true",
                    ADMIN_USER, ADMIN_PASS);
            java.sql.ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT host FROM mysql.user WHERE user = '" + TEST_USER + "'");
            while (rs.next()) {
                conn.createStatement().execute(
                        "DROP USER IF EXISTS '" + TEST_USER + "'@'" + rs.getString("host") + "'");
            }
            rs.close();
            conn.close();
        } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    void trace_t6_create_user() {
        MysqlEngine engine = new MysqlEngine();
        engine.addDataSource("admin", HOST, PORT, "mysql", ADMIN_USER, ADMIN_PASS);
        engine.setDefaultDataSourceName("admin");

        var userMgr = engine.user();
        System.out.println("=== T6: userMgr class=" + userMgr.getClass().getName());
        System.out.println("=== T6: admin ds url=" + getDsUrl(engine));

        List<UserInfo> before = userMgr.listUsers();
        System.out.println("T6: users before = " + before.size());
        before.forEach(u -> System.out.println("  - " + u.getUser() + "@" + u.getHost()));

        userMgr.createUser(TEST_USER).withPassword("engine_test_pass").execute();

        List<UserInfo> after = userMgr.listUsers();
        System.out.println("T6: users after = " + after.size());
        after.forEach(u -> System.out.println("  - " + u.getUser() + "@" + u.getHost()));
    }

    @Test
    @Order(2)
    void trace_t7_grant() {
        MysqlEngine engine = new MysqlEngine();
        engine.addDataSource("admin", HOST, PORT, "mysql", ADMIN_USER, ADMIN_PASS);
        engine.setDefaultDataSourceName("admin");

        // first verify user exists
        var userMgr = engine.user();
        List<UserInfo> users = userMgr.listUsers();
        System.out.println("=== T7 start: users = " + users.size());
        users.forEach(u -> System.out.println("  - " + u.getUser() + "@" + u.getHost()));

        boolean exists = users.stream().anyMatch(u -> TEST_USER.equals(u.getUser()));
        System.out.println("T7: test user exists? " + exists);

        var permMgr = engine.permission();
        System.out.println("T7: permMgr class=" + permMgr.getClass().getName());
        System.out.println("T7: admin ds url=" + getDsUrl(engine));

        try {
            permMgr.grant("SELECT").toUser(TEST_USER).onDatabase(TEST_DB).execute();
            System.out.println("T7: GRANT succeeded");
        } catch (Exception e) {
            System.out.println("T7: GRANT failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String getDsUrl(MysqlEngine engine) {
        try {
            com.chua.common.support.lang.datasource.engine.EngineDataSource<?> eds =
                    engine.getDataSource("admin");
            if (eds != null && eds.getSource() instanceof com.zaxxer.hikari.HikariDataSource hs) {
                return hs.getJdbcUrl();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }
}
