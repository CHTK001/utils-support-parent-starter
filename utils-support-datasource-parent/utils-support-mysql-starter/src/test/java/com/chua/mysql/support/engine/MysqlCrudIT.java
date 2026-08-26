package com.chua.mysql.support.engine;

import com.chua.datasource.support.annotation.TableName;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MysqlEngine 完整增删改查值校验测试。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlCrudIT {

    static final String HOST = "172.16.0.40";
    static final String TABLE = "mysql_engine_crud";
    static MysqlEngine engine;

    @TableName(TABLE)
    public static class MU {
        private int id;
        private String name;
        private int age;
        public int getId() { return id; }
        public void setId(int v) { id = v; }
        public String getName() { return name; }
        public void setName(String n) { name = n; }
        public int getAge() { return age; }
        public void setAge(int a) { age = a; }
    }

    @BeforeAll
    static void setupAll() throws Exception {
        Assumptions.assumeTrue(reachable(HOST, 3308), "MySQL 不可达");
        /* 用原始 JDBC 建表和插入，确保数据存在 */
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":3308/testdb?useSSL=false&allowPublicKeyRetrieval=true",
                "root", "root")) {
            var st = conn.createStatement();
            st.execute("DROP TABLE IF EXISTS " + TABLE);
            st.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, name VARCHAR(20), age INT)");
            st.execute("INSERT INTO " + TABLE + " VALUES (1,'Alice',20),(2,'Bob',30),(3,'Cathy',25)");
        }
        engine = new MysqlEngine();
        engine.addDataSource("mysql", HOST, 3308, "testdb", "root", "root");
    }

    @AfterAll
    static void teardownAll() {
        try { engine.getExecutor().execute("DROP TABLE IF EXISTS " + TABLE); } catch (Exception ignored) {}
        if (engine != null) engine.close();
    }

    private static boolean reachable(String host, int port) {
        try { new Socket().connect(new InetSocketAddress(host, port), 2000); return true; }
        catch (Exception e) { return false; }
    }

    @Test @Order(1)
    void create_andReadValues() {
        List<MU> all = engine.query(MU.class).list();
        assertEquals(3, all.size());

        MU one = engine.query(MU.class).eq(MU::getId, 1).one();
        assertNotNull(one);
        assertEquals("Alice", one.getName());
        assertEquals(20, one.getAge());

        /* 条件过滤 */
        assertEquals(2, engine.query(MU.class).gt(MU::getAge, 22).list().size(), "gt");
        assertEquals(1, engine.query(MU.class).like(MU::getName, "lic").list().size(), "like");
        assertEquals(2, engine.query(MU.class).in(MU::getId, List.of(1, 3)).list().size(), "in");
        assertEquals(2, engine.query(MU.class).between(MU::getAge, 20, 25).list().size(), "between");

        /* 排序 */
        var asc = engine.query(MU.class).orderByAsc(MU::getAge).one();
        assertNotNull(asc);
        assertEquals(20, asc.getAge());
    }

    @Test @Order(2)
    void update_readBackNewValue() {
        int u = engine.update(MU.class)
                .set(MU::getName, "Alicia")
                .eq(MU::getId, 1)
                .update();
        assertEquals(1, u);

        MU after = engine.query(MU.class).eq(MU::getId, 1).one();
        assertNotNull(after);
        assertEquals("Alicia", after.getName(), "update 后应读到新值");

        /* 还原 */
        engine.update(MU.class).set(MU::getName, "Alice").eq(MU::getId, 1).update();
    }

    @Test @Order(3)
    void delete_verifyGone() {
        engine.getExecutor().execute("INSERT INTO " + TABLE + " VALUES (99,'Temp',0)");
        int d = engine.delete(MU.class).eq(MU::getId, 99).remove();
        assertTrue(d >= 0);

        List<MU> rest = engine.query(MU.class).list();
        assertEquals(3, rest.size(), "删除后应剩 3 条");
    }

    @Test @Order(4)
    void metaData_queries() {
        var meta = engine.meta();
        assertNotNull(meta);
        assertDoesNotThrow(() -> meta.table());
        assertDoesNotThrow(() -> meta.view());
        assertDoesNotThrow(() -> meta.index());
    }

    @Test @Order(5)
    void upsert_onDuplicateKeyUpdate() {
        var ex = engine.getExecutor();
        ex.execute("INSERT INTO " + TABLE + " (id,name,age) VALUES (10,'Upsert',50) " +
                "ON DUPLICATE KEY UPDATE name=VALUES(name), age=VALUES(age)");

        MU upserted = engine.query(MU.class).eq(MU::getId, 10).one();
        assertNotNull(upserted);
        assertEquals("Upsert", upserted.getName());

        /* 二次 upsert 覆盖 */
        ex.execute("INSERT INTO " + TABLE + " (id,name,age) VALUES (10,'Updated',55) " +
                "ON DUPLICATE KEY UPDATE name=VALUES(name), age=VALUES(age)");
        MU updated = engine.query(MU.class).eq(MU::getId, 10).one();
        assertNotNull(updated);
        assertEquals("Updated", updated.getName(), "upsert 后应读到新值");
        assertEquals(55, updated.getAge());

        /* 清理 */
        ex.execute("DELETE FROM " + TABLE + " WHERE id=10");
    }
}
