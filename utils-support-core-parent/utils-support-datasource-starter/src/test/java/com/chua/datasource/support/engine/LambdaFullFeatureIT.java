package com.chua.datasource.support.engine;

import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lambda 包装器全操作符矩阵集成测试。
 * 同一数据集在 H2(R2DBC)/MySQL(JDBC)/PostgreSQL(R2DBC $n) 三条真实执行路径上验证：
 * eq/ne/gt/ge/lt/le/like/likeLeft/likeRight/in/notIn/isNull/isNotNull/between/
 * 嵌套or/嵌套and/orderByAsc/orderByDesc/update 多set/delete 条件删除。
 */
class LambdaFullFeatureIT {

    private static final String TABLE = "lamuser";

    /** 统一数据集（city 可为 null 用于 IS NULL 系） */
    private static final Object[][] ROWS = {
            {1, "Alice", 20, null},
            {2, "Bob", 30, "SH"},
            {3, "Cathy", 25, "BJ"},
            {4, "Dave", 35, "SZ"},
            {5, "Eve", 28, null},
    };

    private interface Op {
        void apply(ReactorLambdaQueryWrapper<LamUser> w);
    }

    private static List<Integer> idsWhere(JdbcReactorEngine engine, Op op) {
        ReactorLambdaQueryWrapper<LamUser> w = engine.query(LamUser.class);
        op.apply(w);
        List<LamUser> list = w.list().collectList().block();
        assertNotNull(list);
        return list.stream().map(LamUser::getId).sorted().toList();
    }

    /** 全操作符断言（表 lamuser 中已有 ROWS 数据） */
    private static void assertOps(JdbcReactorEngine engine) {
        assertEquals(List.of(1), idsWhere(engine, w -> w.eq(LamUser::getName, "Alice")), "eq");
        assertEquals(List.of(2, 3, 4, 5), idsWhere(engine, w -> w.ne(LamUser::getName, "Alice")), "ne");
        assertEquals(List.of(2, 4, 5), idsWhere(engine, w -> w.gt(LamUser::getAge, 25)), "gt");
        assertEquals(List.of(2, 4, 5), idsWhere(engine, w -> w.ge(LamUser::getAge, 28)), "ge");
        assertEquals(List.of(1, 3), idsWhere(engine, w -> w.lt(LamUser::getAge, 28)), "lt");
        assertEquals(List.of(1, 3), idsWhere(engine, w -> w.le(LamUser::getAge, 25)), "le");
        assertEquals(List.of(4), idsWhere(engine, w -> w.like(LamUser::getName, "av")), "like");
        assertEquals(List.of(1), idsWhere(engine, w -> w.likeLeft(LamUser::getName, "ce")), "likeLeft");
        assertEquals(List.of(2), idsWhere(engine, w -> w.likeRight(LamUser::getName, "Bo")), "likeRight");
        assertEquals(List.of(1, 3), idsWhere(engine,
                w -> w.in(LamUser::getAge, List.of(20, 25))), "in");
        assertEquals(List.of(3, 4, 5), idsWhere(engine,
                w -> w.notIn(LamUser::getAge, List.of(20, 30))), "notIn");
        assertEquals(List.of(1, 5), idsWhere(engine, w -> w.isNull(LamUser::getCity)), "isNull");
        assertEquals(List.of(2, 3, 4), idsWhere(engine, w -> w.isNotNull(LamUser::getCity)), "isNotNull");
        assertEquals(List.of(2, 3, 5), idsWhere(engine,
                w -> w.between(LamUser::getAge, 25, 30)), "between");
        assertEquals(List.of(1, 2), idsWhere(engine,
                w -> w.or(n -> n.eq(LamUser::getName, "Alice").eq(LamUser::getName, "Bob"))), "or嵌套");
        assertEquals(List.of(2, 3, 5), idsWhere(engine,
                w -> w.and(n -> n.gt(LamUser::getAge, 24).le(LamUser::getAge, 30))), "and嵌套");

        LamUser firstAsc = engine.query(LamUser.class).orderByAsc(LamUser::getAge).one().block();
        assertNotNull(firstAsc);
        assertEquals(20, firstAsc.getAge(), "orderByAsc");
        LamUser firstDesc = engine.query(LamUser.class).orderByDesc(LamUser::getAge).one().block();
        assertNotNull(firstDesc);
        assertEquals(35, firstDesc.getAge(), "orderByDesc");

        Integer updated = engine.update(LamUser.class)
                .set(LamUser::getName, "Alicia")
                .set(LamUser::getCity, "HZ")
                .eq(LamUser::getId, 1)
                .update()
                .block();
        assertEquals(1, updated, "update 影响行数");
        LamUser after = engine.query(LamUser.class).eq(LamUser::getId, 1).one().block();
        assertNotNull(after);
        assertEquals("Alicia", after.getName());
        assertEquals("HZ", after.getCity());

        Integer deleted = engine.delete(LamUser.class)
                .eq(LamUser::getId, 5)
                .remove()
                .block();
        assertEquals(1, deleted, "delete 影响行数");
        List<LamUser> rest = engine.query(LamUser.class).list().collectList().block();
        assertNotNull(rest);
        assertFalse(rest.stream().anyMatch(u -> u.getId() == 5), "删除后不应包含 id=5");
    }

    // ==================== H2：纯 R2DBC 路径 ====================

    @Test
    void h2_fullOperatorMatrix() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("h2", "r2dbc:h2:mem://lam_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try {
            engine.execute("CREATE TABLE lamuser (id INT PRIMARY KEY, name VARCHAR(20), age INT, city VARCHAR(20))").block();
            seed(engine);
            assertOps(engine);
        } finally {
            engine.close();
        }
    }

    // ==================== MySQL：JDBC 路径 ====================

    @Test
    void mysql_fullOperatorMatrix() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        try {
            engine.addDataSource("mysql",
                    "jdbc:mysql://172.16.0.40:3306/report?useSSL=false&allowPublicKeyRetrieval=true",
                    "root", "root@");
            engine.execute("DROP TABLE IF EXISTS lamuser").block();
            engine.execute("CREATE TABLE lamuser (id INT PRIMARY KEY, name VARCHAR(20), age INT, city VARCHAR(20))").block();
            seed(engine);
            assertOps(engine);
            engine.execute("DROP TABLE lamuser").block();
        } finally {
            engine.close();
        }
    }

    // ==================== PostgreSQL：R2DBC $n 占位符路径 ====================

    @Test
    void pg_fullOperatorMatrix() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        try {
            engine.addDataSource("pg", "jdbc:postgresql://172.16.0.40:5433/testdb", "postgres", "postgres");
            engine.execute("DROP TABLE IF EXISTS lamuser").block();
            engine.execute("CREATE TABLE lamuser (id INT PRIMARY KEY, name VARCHAR(20), age INT, city VARCHAR(20))").block();
            seed(engine);
            assertOps(engine);
            engine.execute("DROP TABLE lamuser").block();
        } finally {
            engine.close();
        }
    }

    // ==================== SQL Server：JDBC 路径 ====================

    @Test
    void mssql_fullOperatorMatrix() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        try {
            engine.addDataSource("mssql",
                    "jdbc:sqlserver://172.16.0.40:1434;databaseName=master;encrypt=false;trustServerCertificate=true",
                    "sa", "YourStrong!Passw0rd");
            engine.execute("IF OBJECT_ID('lamuser','U') IS NOT NULL DROP TABLE lamuser").block();
            engine.execute("CREATE TABLE lamuser (id INT PRIMARY KEY, name VARCHAR(20), age INT, city VARCHAR(20))").block();
            seed(engine);
            assertOps(engine);
            engine.execute("DROP TABLE lamuser").block();
        } finally {
            engine.close();
        }
    }

    // ==================== MariaDB：JDBC 路径（mariadb 协议） ====================

    @Test
    void mariadb_fullOperatorMatrix() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        try {
            engine.addDataSource("mariadb",
                    "jdbc:mariadb://172.16.0.40:3308/testdb?useSSL=false&allowPublicKeyRetrieval=true",
                    "root", "root");
            engine.execute("DROP TABLE IF EXISTS lamuser").block();
            engine.execute("CREATE TABLE lamuser (id INT PRIMARY KEY, name VARCHAR(20), age INT, city VARCHAR(20))").block();
            seed(engine);
            assertOps(engine);
            engine.execute("DROP TABLE lamuser").block();
        } finally {
            engine.close();
        }
    }

    private static void seed(JdbcReactorEngine engine) {
        for (Object[] r : ROWS) {
            String city = r[3] == null ? "NULL" : "'" + r[3] + "'";
            engine.execute("INSERT INTO " + TABLE + " VALUES ("
                    + r[0] + ", '" + r[1] + "', " + r[2] + ", " + city + ")").block();
        }
    }
}
