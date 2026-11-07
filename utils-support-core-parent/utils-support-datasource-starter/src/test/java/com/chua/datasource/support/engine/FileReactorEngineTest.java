// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.page.Page;
import com.chua.datasource.support.annotation.TableName;

import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileReactorEngine} 行为约定。
 *
 * <p>响应式文件引擎有两条完全独立的加载通道，各有一类典型缺陷：</p>
 * <ul>
 *   <li><b>异步通道</b>（JSON / CSV / TSV）— 用 {@code AsynchronousFileChannel} 读字节后自己解析。
 *       解析器与同步 {@link FileEngine} 不是同一份实现，因此必须单独验证：CSV 引号包裹、
 *       空行、列数不齐、JSON 单对象退化。</li>
 *   <li><b>回退通道</b>（其他格式）— 调度到 {@code boundedElastic} 走同步 {@code load}。</li>
 * </ul>
 *
 * <p>此外还验证包装器平移：{@code limit}/{@code offset}/分页不能因为「只是包了一层」而丢失。</p>
 * @author CH
 * @since 4.0.0.50
 */
class FileReactorEngineTest {

    private static final String CSV = "id,name,age,email\n"
            + "1,张三,18,zhangsan@example.com\n"
            + "2,李四,25,lisi@example.com\n"
            + "3,王五,,\n"
            + "4,赵六,30,zhaoliu@example.com\n"
            + "5,李七,35,liqi@example.com\n";

    private static final String JSON = "["
            + "{\"id\":1,\"name\":\"张三\",\"age\":18,\"email\":\"zhangsan@example.com\"},"
            + "{\"id\":2,\"name\":\"李四\",\"age\":25,\"email\":\"lisi@example.com\"},"
            + "{\"id\":3,\"name\":\"王五\",\"age\":null,\"email\":\"wangwu@example.com\"},"
            + "{\"id\":4,\"name\":\"赵六\",\"age\":30,\"email\":\"zhaoliu@example.com\"},"
            + "{\"id\":5,\"name\":\"李七\",\"age\":35,\"email\":null}"
            + "]";

    @TempDir
    Path tempDir;

    private FileReactorEngine engine;

    @BeforeEach
    void setUp() {
        engine = new FileReactorEngine();
    }

    private File write(String name, String content) throws Exception {
        File file = tempDir.resolve(name).toFile();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("异步加载 CSV：首行为列名，空字符串归一为 null")
    void loadCsv() throws Exception {
        File file = write("user.csv", CSV);
        engine.load("user", file.getAbsolutePath()).block();

        List<RUser> all = engine.query(RUser.class).list().collectList().block();
        assertNotNull(all);
        assertEquals(5, all.size());
        assertEquals("张三", all.getFirst().getName());

        // CSV 里的空字段必须表现为 null，而不是空字符串
        RUser three = engine.query(RUser.class).eq(RUser::getId, 3).one().block();
        assertNotNull(three);
        assertEquals("王五", three.getName());
        assertNull(three.getAge());
        assertNull(three.getEmail());
    }

    @Test
    @DisplayName("异步加载 CSV：引号包裹的字段内逗号不被切分")
    void loadCsvQuoted() throws Exception {
        File file = write("quoted.csv",
                "id,name,age,email\n1,\"张,三\",18,zhangsan@example.com\n2,李四,25,lisi@example.com\n");
        engine.load("quoted", file.getAbsolutePath()).block();

        List<RUser> all = engine.query(RUser.class).list().collectList().block();
        assertNotNull(all);
        assertEquals(2, all.size());
        assertEquals("张,三", all.getFirst().getName());
    }

    @Test
    @DisplayName("异步加载 CSV：跳过空行，列数不足的行不越界")
    void loadCsvSparse() throws Exception {
        File file = write("sparse.csv",
                "id,name,age,email\n1,张三,18,zhangsan@example.com\n\n2,李四\n");
        engine.load("sparse", file.getAbsolutePath()).block();

        List<RUser> all = engine.query(RUser.class).list().collectList().block();
        assertNotNull(all);
        assertEquals(2, all.size());
        assertEquals("李四", all.get(1).getName());
        assertNull(all.get(1).getAge());
        assertNull(all.get(1).getEmail());
    }

    @Test
    @DisplayName("异步加载 TSV：以制表符分隔")
    void loadTsv() throws Exception {
        File file = write("user.tsv",
                "id\tname\tage\temail\n1\t张三\t18\tzhangsan@example.com\n2\t李四\t25\tlisi@example.com\n");
        engine.load("tsv", file.getAbsolutePath()).block();

        List<RUser> all = engine.query(RUser.class).orderByAsc(RUser::getId)
                .list().collectList().block();
        assertNotNull(all);
        assertEquals(2, all.size());
        assertEquals("张三", all.getFirst().getName());
        assertEquals(25, all.get(1).getAge());
    }

    @Test
    @DisplayName("异步加载 JSON 数组")
    void loadJsonArray() throws Exception {
        File file = write("user.json", JSON);
        engine.load("json", file.getAbsolutePath()).block();

        List<RUser> all = engine.query(RUser.class).list().collectList().block();
        assertNotNull(all);
        assertEquals(5, all.size());

        RUser three = engine.query(RUser.class).eq(RUser::getId, 3).one().block();
        assertNotNull(three);
        assertNull(three.getAge());

        RUser five = engine.query(RUser.class).eq(RUser::getId, 5).one().block();
        assertNotNull(five);
        assertNull(five.getEmail());
    }

    @Test
    @DisplayName("异步加载 JSON 单对象时退化为单行")
    void loadJsonSingleObject() throws Exception {
        File file = write("single.json",
                "{\"id\":7,\"name\":\"周七\",\"age\":41,\"email\":\"zhouqi@example.com\"}");
        engine.load("single", file.getAbsolutePath()).block();

        List<RUser> all = engine.query(RUser.class).list().collectList().block();
        assertNotNull(all);
        assertEquals(1, all.size());
        assertEquals("周七", all.getFirst().getName());
        assertEquals(41, all.getFirst().getAge());
    }

    @Test
    @DisplayName("JSON 内容损坏时以错误信号终止，并带上数据源名")
    void loadJsonBroken() throws Exception {
        File file = write("broken.json", "{not-json");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> engine.load("broken", file.getAbsolutePath()).block());
        assertTrue(e.getMessage().contains("broken"));
    }

    @Test
    @DisplayName("CSV 只有表头没有数据行时明确报错，而不是静默成功")
    void loadCsvHeaderOnly() throws Exception {
        File file = write("header_only.csv", "id,name,age,email\n");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> engine.load("h", file.getAbsolutePath()).block());
        assertTrue(e.getMessage().contains("h"));
    }

    @Test
    @DisplayName("其他扩展名回退到同步加载通道")
    void loadFallback() throws Exception {
        // json5 不在异步解析白名单（只认 json/csv/tsv），走 boundedElastic 上的同步 load
        File file = write("user.json5", JSON);
        assertNotNull(engine.load("j5", file.getAbsolutePath()).block());
        List<RUser> all = engine.query(RUser.class).list().collectList().block();
        assertNotNull(all);
        assertEquals(5, all.size());
    }

    @Test
    @DisplayName("Lambda 条件、排序、limit、offset 全部平移（曾整体丢失）")
    void queryConditions() throws Exception {
        engine.load("user", write("user.csv", CSV).getAbsolutePath()).block();

        assertEquals(List.of(2), ids(engine.query(RUser.class)
                .eq(RUser::getName, "李四").list().collectList().block()));

        assertEquals(List.of(5, 4, 2), ids(engine.query(RUser.class)
                .gt(RUser::getAge, 20).orderByDesc(RUser::getAge)
                .list().collectList().block()));

        assertEquals(List.of(1, 2), ids(engine.query(RUser.class)
                .orderByAsc(RUser::getId).limit(2).list().collectList().block()));

        assertEquals(List.of(2, 3), ids(engine.query(RUser.class)
                .orderByAsc(RUser::getId).limit(2).offset(1).list().collectList().block()));

        assertTrue(engine.query(RUser.class).orderByAsc(RUser::getId)
                .limit(2).offset(99).list().collectList().block().isEmpty());
    }

    @Test
    @DisplayName("分页：total 为命中总数，第二页有数据，越界页为空")
    void page() throws Exception {
        engine.load("user", write("user.csv", CSV).getAbsolutePath()).block();

        Page<RUser> second = engine.query(RUser.class).orderByAsc(RUser::getId).page(2, 2).block();
        assertNotNull(second);
        assertEquals(2, second.getPageNum());
        assertEquals(5, second.getTotal());
        assertEquals(List.of(3, 4), ids(second.getRecords()));

        Page<RUser> first = engine.query(RUser.class).orderByAsc(RUser::getId).page(1, 2).block();
        assertNotNull(first);
        assertEquals(List.of(1, 2), ids(first.getRecords()));

        Page<RUser> beyond = engine.query(RUser.class).page(9, 2).block();
        assertNotNull(beyond);
        assertEquals(5, beyond.getTotal());
        assertTrue(beyond.getRecords().isEmpty());

        Page<RUser> clamp = engine.query(RUser.class).orderByAsc(RUser::getId).page(0, 2).block();
        assertNotNull(clamp);
        assertEquals(1, clamp.getPageNum());

        assertThrows(IllegalArgumentException.class,
                () -> engine.query(RUser.class).page(1, 0).block());
    }

    @Test
    @DisplayName("update / delete 经包装器执行，并写回文件")
    void updateAndDelete() throws Exception {
        File file = write("user.csv", CSV);
        engine.load("user", file.getAbsolutePath()).block();

        Integer updated = engine.update(RUser.class)
                .set(RUser::getAge, 45)
                .eq(RUser::getId, 4)
                .update().block();
        assertEquals(1, updated);
        assertEquals(45, engine.query(RUser.class).eq(RUser::getId, 4).one().block().getAge());

        Integer removed = engine.delete(RUser.class).eq(RUser::getId, 2).remove().block();
        assertEquals(1, removed);
        assertEquals(4, engine.query(RUser.class).count().block());

        // 写回必须真的落到文件：新引擎重新加载后看不到被删的行
        FileReactorEngine reloaded = new FileReactorEngine();
        reloaded.load("user", file.getAbsolutePath()).block();
        assertNull(reloaded.query(RUser.class).eq(RUser::getId, 2).one().block());
        assertEquals(45, reloaded.query(RUser.class).eq(RUser::getId, 4).one().block().getAge());
    }

    @Test
    @DisplayName("原生 SQL：查询、类型化查询、execute、batch")
    void rawSql() throws Exception {
        engine.load("user", write("user.csv", CSV).getAbsolutePath()).block();

        List<Map<String, Object>> rows = engine.query(
                "SELECT * FROM user WHERE age >= ? ORDER BY age", 25)
                .collectList().block();
        assertNotNull(rows);
        assertEquals(3, rows.size());

        List<RUser> typed = engine.query(
                "SELECT * FROM user WHERE name LIKE ?", RUser.class, "%王%")
                .collectList().block();
        assertNotNull(typed);
        assertEquals(1, typed.size());
        assertEquals(3, typed.getFirst().getId());

        Integer affected = engine.execute(
                "UPDATE user SET email = ? WHERE id = ?", "a@b.c", 1).block();
        assertEquals(1, affected);
        assertEquals("a@b.c", engine.query(RUser.class).eq(RUser::getId, 1).one().block().getEmail());

        List<Integer> batch = engine.batch("UPDATE user SET age = ? WHERE id = ?",
                List.of(new Object[]{50, 1}, new Object[]{60, 2}))
                .collectList().block();
        assertNotNull(batch);
        assertEquals(2, batch.size());
        assertEquals(50, engine.query(RUser.class).eq(RUser::getId, 1).one().block().getAge());
        assertEquals(60, engine.query(RUser.class).eq(RUser::getId, 2).one().block().getAge());
    }

    @Test
    @DisplayName("未加载任何文件时查询返回空结果而不是抛异常")
    void noData() {
        assertTrue(engine.query(RUser.class).list().collectList().block().isEmpty());
        assertEquals(0, engine.query(RUser.class).count().block());
        assertNull(engine.query(RUser.class).one().block());
    }

    @Test
    @DisplayName("加载不存在的文件以错误信号终止")
    void loadMissingFile() {
        assertThrows(Exception.class,
                () -> engine.load("x", tempDir.resolve("nope.csv").toString()).block());
    }

    @Test
    @DisplayName("count 与 one 在无命中时给出确定结果")
    void emptyResults() throws Exception {
        engine.load("user", write("user.csv", CSV).getAbsolutePath()).block();

        assertEquals(0, engine.query(RUser.class).eq(RUser::getId, 99).count().block());
        assertNull(engine.query(RUser.class).eq(RUser::getId, 99).one().block());
        assertFalse(engine.query(RUser.class).eq(RUser::getId, 99)
                .list().collectList().block().iterator().hasNext());
    }

    private static List<Integer> ids(List<RUser> list) {
        assertNotNull(list);
        return list.stream().map(RUser::getId).toList();
    }

    /**
     * 测试实体（表名 user）。
     */
    @TableName("user")
    public static class RUser {
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

        /** setAge 方法 */
        public void setAge(Integer age) {
            this.age = age;
        }

        /** getEmail 方法 */
        public String getEmail() {
            return email;
        }

        /** setEmail 方法 */
        public void setEmail(String email) {
            this.email = email;
        }
    }
}