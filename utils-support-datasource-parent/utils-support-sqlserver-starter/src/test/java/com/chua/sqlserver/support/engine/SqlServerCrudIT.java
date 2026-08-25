package com.chua.sqlserver.support.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlServerEngine CRUD 值校验测试（SqlExecutor 直连路径）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlServerCrudIT {

    private static SqlServerEngine engine;

    @BeforeAll
    static void setup() {
        engine = new SqlServerEngine();
        engine.addDataSource("mssql", "172.16.0.40", 1434, "master", "sa", "YourStrong!Passw0rd");
        var ex = engine.getExecutor();
        try { ex.execute("IF OBJECT_ID('ss_crud','U') IS NOT NULL DROP TABLE ss_crud"); } catch (Exception ignored) {}
        ex.execute("CREATE TABLE ss_crud (id INT PRIMARY KEY, name NVARCHAR(20), age INT)");
        ex.execute("INSERT INTO ss_crud VALUES (1,'Alice',20),(2,'Bob',30)");
    }

    @AfterAll
    static void teardown() {
        try { engine.getExecutor().execute("IF OBJECT_ID('ss_crud','U') IS NOT NULL DROP TABLE ss_crud"); } catch (Exception ignored) {}
        engine.close();
    }

    @Test
    @Order(1)
    void read_values() {
        List<Map<String,Object>> rows = engine.getExecutor()
                .query("SELECT id,name,age FROM ss_crud ORDER BY id");
        assertEquals(2, rows.size());
        assertEquals("Alice", rows.get(0).get("name"));
        assertEquals(30, ((Number)rows.get(1).get("age")).intValue());
    }

    @Test
    @Order(2)
    void update_readBack() {
        int u = engine.getExecutor()
                .execute("UPDATE ss_crud SET name='Alicia' WHERE id=1");
        assertEquals(1, u);

        var row = engine.getExecutor()
                .query("SELECT name FROM ss_crud WHERE id=1").get(0);
        assertEquals("Alicia", row.get("name"), "update 后应读到新值");
    }

    @Test
    @Order(3)
    void delete_verifyGone() {
        int d = engine.getExecutor()
                .execute("DELETE FROM ss_crud WHERE id=2");
        assertEquals(1, d);

        List<Map<String,Object>> rest = engine.getExecutor()
                .query("SELECT id FROM ss_crud");
        assertEquals(1, rest.size());
    }
}
