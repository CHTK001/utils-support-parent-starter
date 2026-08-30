package com.chua.mysql.support.engine;

import com.chua.datasource.support.permission.PermissionInfo;
import org.junit.jupiter.api.*;
import org.opentest4j.TestSkippedException;

import java.util.List;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcEnginePerfDebugTest {

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
            throw new TestSkippedException("MySQL unreachable");
        }
    }

    @Test
    void debug_perms() throws Exception {
        com.chua.mysql.support.engine.MysqlEngine engine = new com.chua.mysql.support.engine.MysqlEngine();
        engine.addDataSource("admin", HOST, PORT, "mysql", ADMIN_USER, ADMIN_PASS);
        engine.setDefaultDataSourceName("admin");

        // create user
        engine.user().createUser(TEST_USER).withPassword("engine_test_pass").execute();

        // grant
        engine.permission().grant("SELECT, INSERT, UPDATE, DELETE")
                .toUser(TEST_USER)
                .onDatabase(TEST_DB)
                .execute();
        System.out.println("GRANT succeeded");

        // list all perms
        var permMgr = engine.permission();
        List<PermissionInfo> all = permMgr.listPermissions();
        System.out.println("All perms: " + all.size());
        all.forEach(p -> System.out.println("  " + p.getUser() + " | db=" + p.getDatabaseName()
                + " | tbl=" + p.getTableName() + " | priv=" + p.getPrivilegeType()));

        List<PermissionInfo> userPerms = permMgr.listPermissions(TEST_USER);
        System.out.println(TEST_USER + " perms: " + userPerms.size());
        userPerms.forEach(p -> System.out.println("  user_perm: " + p.getUser() + " | db=" + p.getDatabaseName()
                + " | tbl=" + p.getTableName() + " | priv=" + p.getPrivilegeType()));

        boolean hasDb = userPerms.stream().anyMatch(p -> TEST_DB.equals(p.getDatabaseName()));
        System.out.println("Has TEST_DB perm: " + hasDb);

        // also check raw SQL
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/mysql?useSSL=false&allowPublicKeyRetrieval=true",
                ADMIN_USER, ADMIN_PASS)) {
            java.sql.ResultSet rs = c.createStatement().executeQuery(
                    "SELECT GRANTEE, TABLE_SCHEMA, PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE LIKE '%engine_test_user%'");
            System.out.println("SCHEMA_PRIVILEGES rows:");
            while (rs.next()) {
                System.out.println("  " + rs.getString("GRANTEE") + " | " + rs.getString("TABLE_SCHEMA")
                        + " | " + rs.getString("PRIVILEGE_TYPE"));
            }
            rs.close();
        }

        engine.close();
    }
}
