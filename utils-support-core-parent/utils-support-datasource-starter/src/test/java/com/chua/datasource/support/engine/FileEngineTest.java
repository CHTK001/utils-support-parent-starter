package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.datasource.support.annotation.TableName;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileEngine} 行为约定。
 *
 * <p>文件引擎把表格文件当作内存表，再在内存上执行 Lambda 条件与原生 SQL，
 * 因此断言要覆盖「内存语义 + 写回文件」两条链路：</p>
 * <ul>
 *   <li>加载：类型白名单、扩展名识别、空字符串归一为 null</li>
 *   <li>查询：Lambda 条件、原生 SQL、类型化映射</li>
 *   <li>变更：UPDATE/DELETE/INSERT 在 autoPersist 下必须真正写回文件（重新加载可见）</li>
 * </ul>
 */
class FileEngineTest {

    private static final String CSV_HEADER = "id,name,age,email\n";

    private static final String CSV_BODY =
            "1,张三,18,zhangsan@example.com\n"
                    + "2,李四,25,lisi@example.com\n"
                    + "3,王五,,\n"
                    + "4,赵六,30,zhaoliu@example.com\n";

    @TempDir
    Path tempDir;

    private File csv(String body) throws Exception {
        File file = tempDir.resolve("user.csv").toFile();
        Files.writeString(file.toPath(), CSV_HEADER + body, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("加载 CSV 后可按 Lambda 条件查询")
    void loadAndQuery() throws Exception {
        FileEngine engine = new FileEngine().load("user", csv(CSV_BODY), null);

        assertEquals(4, engine.query(User.class).count());
        assertEquals(List.of(2), ids(engine.query(User.class).eq(User::getName, "李四").list()));
        assertEquals(List.of(2), ids(engine.query(User.class).gt(User::getAge, 20).list()
                .stream().filter(u -> u.getAge() < 30).toList()));
        // CSV 空字段加载后归一为 null，而不是空字符串
        assertNull(engine.query(User.class).eq(User::getId, 3).one().getEmail());
    }

    @Test
    @DisplayName("加载 CSV 后可按原生 SQL 查询，并映射为实体")
    void nativeSql() throws Exception {
        FileEngine engine = new FileEngine().load("user", csv(CSV_BODY), null);

        List<Map<String, Object>> rows = engine.querySql(
                "SELECT * FROM user WHERE age >= ? ORDER BY age", 25);
        assertEquals(2, rows.size());
        assertEquals("李四", rows.getFirst().get("name"));

        List<User> users = engine.querySql(
                "SELECT * FROM user WHERE name LIKE ?", User.class, "%王%");
        assertEquals(1, users.size());
        assertEquals(3, users.getFirst().getId());

        assertThrows(IllegalArgumentException.class, () -> engine.querySql((String) null));
        assertThrows(IllegalArgumentException.class,
                () -> engine.querySql("SELECT * FROM user", (Class<User>) null));
        assertThrows(IllegalArgumentException.class,
                () -> engine.querySql("SELECT 1", null, new Object[0]));
    }

    @Test
    @DisplayName("原生 DML 修改内存并自动写回文件")
    void executeSqlPersists() throws Exception {
        File file = csv(CSV_BODY);
        FileEngine engine = new FileEngine().load("user", file, null);

        assertEquals(1, engine.executeSql("UPDATE user SET age = 99 WHERE id = ?", 1));
        String afterUpdate = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(afterUpdate.contains("99"));

        assertEquals(1, engine.executeSql("DELETE FROM user WHERE id = ?", 2));
        String afterDelete = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertFalse(afterDelete.contains("lisi@example.com"));

        // 重新加载可读到写入结果，证明是持久化而不是仅内存变更
        FileEngine reloaded = new FileEngine().load("user", file, null);
        assertEquals(99, reloaded.query(User.class).eq(User::getId, 1).one().getAge());
        assertEquals(3, reloaded.query(User.class).count());

        assertThrows(NullPointerException.class, () -> engine.executeSql(null));
        assertThrows(IllegalStateException.class,
                () -> engine.executeSql("DELETE FROM notLoaded WHERE id = ?", 1));
    }

    @Test
    @DisplayName("Lambda 更新/删除触发自动持久化")
    void lambdaMutatePersists() throws Exception {
        File file = csv(CSV_BODY);
        FileEngine engine = new FileEngine().load("user", file, null);

        assertEquals(1, engine.update(User.class).set(User::getAge, 77)
                .eq(User::getId, 4).update());
        assertEquals(1, engine.delete(User.class).eq(User::getId, 1).remove());

        FileEngine reloaded = new FileEngine().load("user", file, null);
        assertEquals(77, reloaded.query(User.class).eq(User::getId, 4).one().getAge());
        assertEquals(3, reloaded.query(User.class).count());
    }

    @Test
    @DisplayName("autoPersist=false 时只改内存不写文件，save 后才落盘")
    void autoPersistDisabled() throws Exception {
        File file = csv(CSV_BODY);
        FileEngine engine = new FileEngine().load("user", file, null).autoPersist(false);

        assertEquals(1, engine.executeUpdate(new UpdateSql<>(User.class,
                "age = 88", "id = ?", List.of(1))));
        assertEquals(88, engine.query(User.class).eq(User::getId, 1).one().getAge());
        assertFalse(Files.readString(file.toPath(), StandardCharsets.UTF_8).contains("88"));

        engine.save("user");
        assertTrue(Files.readString(file.toPath(), StandardCharsets.UTF_8).contains("88"));
    }

    @Test
    @DisplayName("按表名关闭自动持久化只影响该表")
    void autoPersistPerTable() throws Exception {
        File file = csv(CSV_BODY);
        FileEngine engine = new FileEngine().load("user", file, null).autoPersist("user", false);

        assertEquals(1, engine.executeDelete(new DeleteSql<>(User.class, "id = ?", List.of(2))));
        assertEquals(3, engine.query(User.class).count());
        // 文件仍保留被删除的行
        assertTrue(Files.readString(file.toPath(), StandardCharsets.UTF_8)
                .contains("lisi@example.com"));

        // 对未加载的表名设置开关是空操作，不抛异常
        engine.autoPersist("missing", false);
    }

    @Test
    @DisplayName("saveAll 覆盖全部已加载表，空表写入仅表头")
    void saveAllAndEmptyTable() throws Exception {
        File file = csv(CSV_BODY);
        FileEngine engine = new FileEngine().load("user", file, null);

        assertEquals(4, engine.executeDelete(new DeleteSql<>(User.class, "id >= ?", List.of(1))));
        engine.saveAll();

        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(CSV_HEADER.trim(), text.trim());

        // 未加载任何表时 save(name) 是空操作
        new FileEngine().save("nothing");
    }

    @Test
    @DisplayName("insert 走内存通道后写回文件")
    void insertPersists() throws Exception {
        File file = csv(CSV_BODY);
        FileEngine engine = new FileEngine().load("user", file, null);

        assertEquals(1, engine.insert(User.class, new User(5, "李七", 35, "liqi@example.com")));
        assertEquals(5, engine.query(User.class).count());

        FileEngine reloaded = new FileEngine().load("user", file, null);
        assertEquals(5, reloaded.query(User.class).count());
        assertEquals("李七", reloaded.query(User.class).eq(User::getId, 5).one().getName());
    }

    @Test
    @DisplayName("实体表名与加载名不一致时仍能定位并写回")
    void entityTableNameResolves() throws Exception {
        File file = tempDir.resolve("t_user.csv").toFile();
        Files.writeString(file.toPath(), CSV_HEADER + CSV_BODY, StandardCharsets.UTF_8);

        FileEngine engine = new FileEngine().load("t_user", file, null);
        // Account 的表名由 @TableName 指定为 t_user，与加载名一致
        assertEquals(4, engine.query(Account.class).count());

        assertEquals(1, engine.executeUpdate(new UpdateSql<>(Account.class,
                "age = 66", "id = ?", List.of(3))));
        assertTrue(Files.readString(file.toPath(), StandardCharsets.UTF_8).contains("66"));
    }

    @Test
    @DisplayName("文件类型解析：扩展名识别、显式类型、白名单校验")
    void resolveSupportedType() {
        assertEquals("csv", FileEngine.resolveSupportedType(new File("a.csv"), null));
        assertEquals("csv", FileEngine.resolveSupportedType(new File("a.tsv"), null));
        assertEquals("excel", FileEngine.resolveSupportedType(new File("a.xlsx"), null));
        assertEquals("excel", FileEngine.resolveSupportedType(new File("a.XLS"), null));
        assertEquals("json", FileEngine.resolveSupportedType(new File("a.json5"), null));
        assertEquals("dbf", FileEngine.resolveSupportedType(new File("a.dbf"), null));
        // 显式类型优先于扩展名，且大小写不敏感
        assertEquals("csv", FileEngine.resolveSupportedType(new File("a.txt"), "CSV"));
        assertEquals("json", FileEngine.resolveSupportedType(new File("a.csv"), "json"));

        assertThrows(UnsupportedOperationException.class,
                () -> FileEngine.resolveSupportedType(new File("a.pdf"), null));
        assertThrows(UnsupportedOperationException.class,
                () -> FileEngine.resolveSupportedType(new File("a.csv"), "pdf"));
        UnsupportedOperationException ambiguous = assertThrows(UnsupportedOperationException.class,
                () -> FileEngine.resolveSupportedType(new File("noext"), null));
        assertTrue(ambiguous.getMessage().contains("无法识别文件类型"));
    }

    @Test
    @DisplayName("不支持的文件类型在 load 阶段即被拒绝")
    void loadRejectsUnsupportedFile() throws Exception {
        File file = tempDir.resolve("a.pdf").toFile();
        Files.writeString(file.toPath(), "not a table", StandardCharsets.UTF_8);

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> new FileEngine().load("t", file, null));
        assertTrue(e.getMessage().contains("仅支持表格文件"));
    }

    @Test
    @DisplayName("能力声明与缺省表查询")
    void capabilities() {
        FileEngine engine = new FileEngine();
        assertTrue(engine.supportsSql());
        // 未加载任何表时查询返回空列表，而不是抛异常
        assertTrue(engine.query(User.class).list().isEmpty());
        assertTrue(engine.querySql("SELECT * FROM user").isEmpty());
    }

    private static List<Integer> ids(List<User> list) {
        return list.stream().map(User::getId).toList();
    }

    /**
     * 测试实体（表名 user）。
     */
    public static class User {
        private Integer id;
        private String name;
        private Integer age;
        private String email;

        public User() {
        }

        User(Integer id, String name, Integer age, String email) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.email = email;
        }

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    /**
     * 表名由注解指定的实体。
     */
    @TableName("t_user")
    public static class Account {
        private Integer id;
        private String name;
        private Integer age;
        private String email;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }
}
