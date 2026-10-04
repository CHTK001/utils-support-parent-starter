import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.datasource.support.annotation.TableName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 引擎验收运行器 —— 阶段二（修正版）：嵌入式引擎的深度功能验收。
 *
 * <p>与初版的区别（初版有 3 类 harness 自身缺陷，会把正确实现判成失败）：
 * <ol>
 *   <li>「预期失败未发生」改用独立的 {@link HarnessFailure} 标记，
 *       不再复用 {@code IllegalStateException} —— 后者与引擎自身的错误类型撞车，
 *       会把引擎正确抛出的异常误判为 harness 断言失败；</li>
 *   <li>按引擎分档（{@link Profile}）：内存/文件引擎无 DDL、无数据源注册表，
 *       应以 {@code store()} 播种，不能拿 JDBC 引擎的期望去要求它们；</li>
 *   <li>失败信息带完整异常链，便于直接定位根因。</li>
 * </ol>
 * </p>
 *
 * <p>退出码 = 失败数（0 表示全通过）。输出同时以 UTF-8 落盘，避免 shell 重定向的编码与长度陷阱。</p>
 */
public final class EngineFunctional {

    /** 验收用实体：表名显式钉死，避免依赖驼峰转换规则。 */
    @TableName("t_acc_user")
    public static class AccUser {
        private Long id;
        private String name;
        private Integer age;

        public AccUser() {
        }

        public AccUser(Long id, String name, Integer age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
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

        @Override
        public String toString() {
            return "AccUser{id=" + id + ",name=" + name + ",age=" + age + '}';
        }
    }

    /** harness 专用失败标记：与任何引擎异常类型都不重叠。 */
    static final class HarnessFailure extends AssertionError {
        HarnessFailure(String message) {
            super(message);
        }
    }

    /**
     * 引擎能力档位。
     *
     * @param ddl       是否支持 CREATE TABLE（JDBC 引擎支持；内存/文件引擎按设计不支持）
     * @param registry  是否维护数据源注册表（dataSourceNames/hasDataSource 有意义）
     * @param seedByStore 是否用 store() 播种（而非 INSERT 语句）
     */
    record Profile(boolean ddl, boolean registry, boolean seedByStore) {
        static final Profile JDBC = new Profile(true, true, false);
        static final Profile INMEM = new Profile(false, false, true);
    }

    private static int pass = 0;
    private static int fail = 0;
    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<String> OUT = new ArrayList<>();

    private EngineFunctional() {
    }

    static void emit(String line) {
        OUT.add(line);
        System.out.println(line);
    }

    public static void main(String[] args) {
        emit("=".repeat(78));
        emit("  引擎验收 阶段二（修正版）：嵌入式引擎深度功能面");
        emit("=".repeat(78));

        Map<String, Runnable> cases = new LinkedHashMap<>();
        cases.put("memory", () -> sqlSuite("memory", () -> Engine.create("memory"), Profile.INMEM));
        cases.put("file", () -> sqlSuite("file", () -> Engine.create("file"), Profile.INMEM));
        cases.put("h2", () -> sqlSuite("h2",
                () -> ((com.chua.h2.support.engine.H2Engine) Engine.create("h2"))
                        .addDataSource("default", "jdbc:h2:mem:accfunc;DB_CLOSE_DELAY=-1"), Profile.JDBC));
        cases.put("sqlite", () -> sqlSuite("sqlite",
                () -> ((com.chua.sqlite.support.engine.SqliteEngine) Engine.create("sqlite"))
                        .addDataSource("default", "target/acc-func.sqlite"), Profile.JDBC));
        cases.put("duckdb", () -> sqlSuite("duckdb",
                () -> ((com.chua.duckdb.support.engine.DuckDBEngine) Engine.create("duckdb"))
                        .addDataSource("default", "jdbc:duckdb:target/acc-func.duckdb"), Profile.JDBC));

        for (Map.Entry<String, Runnable> entry : cases.entrySet()) {
            try {
                entry.getValue().run();
            } catch (Throwable t) {
                recordFail(entry.getKey() + " 套件异常退出: " + chain(t));
            }
        }

        emit("=".repeat(78));
        emit("  结果: PASS=" + pass + "  FAIL=" + fail);
        for (String f : FAILURES) {
            emit("    [FAIL] " + f);
        }
        emit("=".repeat(78));
        writeReport();
        System.exit(fail);
    }

    /** 一个引擎的完整功能面。 */
    static void sqlSuite(String key, Init init, Profile profile) {
        emit("--- " + key + " (" + (profile.seedByStore() ? "内存/文件档" : "JDBC 档") + ") ---");
        Engine e;
        try {
            e = init.create();
        } catch (Throwable t) {
            recordFail(key + " 初始化失败: " + chain(t));
            return;
        }
        if (e == null) {
            recordFail(key + " 初始化返回 null");
            return;
        }
        try {
            check(key + ".supportsSql", () -> {
                if (!e.supportsSql()) {
                    throw new HarnessFailure("supportsSql()=false（挂载后应支持原生语句）");
                }
                return "true";
            });

            // ---------- 播种 ----------
            if (profile.seedByStore()) {
                check(key + ".store(播种 5 行)", () -> {
                    e.store("t_acc_user", List.of(
                            new AccUser(1L, "alice", 30),
                            new AccUser(2L, "bob", 25),
                            new AccUser(3L, "carol", 35),
                            new AccUser(4L, "dave", 40),
                            new AccUser(5L, "erin", 45)));
                    return "stored=5";
                });
            } else {
                check(key + ".executeSql(DROP+CREATE TABLE)", () -> {
                    try {
                        e.executeSql("DROP TABLE IF EXISTS t_acc_user");
                    } catch (RuntimeException ignored) {
                        // 首次运行表不存在
                    }
                    e.executeSql("CREATE TABLE t_acc_user (id INT, name VARCHAR(64), age INT)");
                    return "created";
                });
                check(key + ".executeSql(INSERT x5)", () -> {
                    int sum = 0;
                    sum += e.executeSql("INSERT INTO t_acc_user (id, name, age) VALUES (?, ?, ?)", 1, "alice", 30);
                    sum += e.executeSql("INSERT INTO t_acc_user (id, name, age) VALUES (?, ?, ?)", 2, "bob", 25);
                    sum += e.executeSql("INSERT INTO t_acc_user (id, name, age) VALUES (?, ?, ?)", 3, "carol", 35);
                    sum += e.executeSql("INSERT INTO t_acc_user (id, name, age) VALUES (?, ?, ?)", 4, "dave", 40);
                    sum += e.executeSql("INSERT INTO t_acc_user (id, name, age) VALUES (?, ?, ?)", 5, "erin", 45);
                    if (sum != 5) {
                        throw new HarnessFailure("五行插入受影响行数合计=" + sum);
                    }
                    return "affected=" + sum;
                });
            }

            // ---------- 读 ----------
            check(key + ".querySql(SELECT *)", () -> {
                List<Map<String, Object>> rows = e.querySql("SELECT * FROM t_acc_user");
                if (rows == null || rows.size() != 5) {
                    throw new HarnessFailure("期望 5 行，实际 " + (rows == null ? "null" : rows.size()));
                }
                return "rows=" + rows.size();
            });

            check(key + ".querySql(WHERE age>?)", () -> {
                List<Map<String, Object>> rows = e.querySql("SELECT * FROM t_acc_user WHERE age > ?", 26);
                if (rows == null || rows.size() != 4) {
                    throw new HarnessFailure("age>26 期望 4 行，实际 " + (rows == null ? "null" : rows.size()));
                }
                return "rows=" + rows.size();
            });

            check(key + ".count(sql)", () -> {
                long n = e.count("SELECT COUNT(*) FROM t_acc_user");
                if (n != 5) {
                    throw new HarnessFailure("COUNT(*) 期望 5，实际 " + n);
                }
                return String.valueOf(n);
            });

            check(key + ".queryValue(标量聚合 MAX)", () -> {
                Object v = e.queryValue("SELECT MAX(age) FROM t_acc_user");
                if (!(v instanceof Number num) || num.intValue() != 45) {
                    throw new HarnessFailure("MAX(age) 期望 45，实际 " + v);
                }
                return String.valueOf(v);
            });

            check(key + ".exists(true/false)", () -> {
                boolean yes = e.exists("SELECT id FROM t_acc_user WHERE name = ?", "alice");
                boolean no = e.exists("SELECT id FROM t_acc_user WHERE name = ?", "nobody");
                if (!yes || no) {
                    throw new HarnessFailure("exists 结果异常: yes=" + yes + " no=" + no);
                }
                return "yes=true no=false";
            });

            check(key + ".querySql(sql, Class) 类型化", () -> {
                List<AccUser> list = e.querySql("SELECT id, name, age FROM t_acc_user ORDER BY id", AccUser.class);
                if (list == null || list.size() != 5) {
                    throw new HarnessFailure("期望 5 条，实际 " + (list == null ? "null" : list.size()));
                }
                // 逐字段校验：只查 String 字段会漏掉「非 String 类型静默映射为 null」这类缺陷
                AccUser first = list.get(0);
                if (first.getId() == null || first.getId() != 1L) {
                    throw new HarnessFailure("id 映射错误: " + first.getId());
                }
                if (!"alice".equals(first.getName())) {
                    throw new HarnessFailure("name 映射错误: " + first.getName());
                }
                if (first.getAge() == null || first.getAge() != 30) {
                    throw new HarnessFailure("age 映射错误: " + first.getAge());
                }
                for (AccUser u : list) {
                    if (u.getId() == null || u.getName() == null || u.getAge() == null) {
                        throw new HarnessFailure("存在字段为 null 的行: " + u);
                    }
                }
                return "size=" + list.size() + " first=" + first;
            });

            check(key + ".queryOne(sql, Class)", () -> {
                AccUser u = e.queryOne("SELECT id, name, age FROM t_acc_user WHERE id = ?", AccUser.class, 2);
                if (u == null || !"bob".equals(u.getName()) || u.getId() == null || u.getAge() == null) {
                    throw new HarnessFailure("期望完整映射 bob/2/25，实际 " + u);
                }
                return u.toString();
            });

            // ---------- 分页 ----------
            check(key + ".queryPage(第1页 2条)", () -> {
                List<Map<String, Object>> p1 = e.queryPage("SELECT * FROM t_acc_user ORDER BY id", 1, 2);
                if (p1 == null || p1.size() != 2) {
                    throw new HarnessFailure("第1页期望 2 行，实际 " + (p1 == null ? "null" : p1.size()));
                }
                return "size=" + p1.size();
            });

            check(key + ".queryPage(第2页 2条)", () -> {
                List<Map<String, Object>> p2 = e.queryPage("SELECT * FROM t_acc_user ORDER BY id", 2, 2);
                if (p2 == null || p2.size() != 2) {
                    throw new HarnessFailure("第2页期望 2 行，实际 " + (p2 == null ? "null" : p2.size()));
                }
                return "size=" + p2.size();
            });

            check(key + ".queryPage(越界页为空)", () -> {
                List<Map<String, Object>> p9 = e.queryPage("SELECT * FROM t_acc_user ORDER BY id", 9, 2);
                if (p9 == null || !p9.isEmpty()) {
                    throw new HarnessFailure("越界页期望空，实际 " + (p9 == null ? "null" : p9.size()));
                }
                return "empty";
            });

            check(key + ".queryPage(参数守卫)", () -> {
                try {
                    e.queryPage("SELECT 1", 0, 10);
                    throw new HarnessFailure("pageNum=0 未抛 IllegalArgumentException");
                } catch (IllegalArgumentException expected) {
                    return "已拒绝";
                }
            });

            // ---------- 写 ----------
            if (!profile.seedByStore()) {
                check(key + ".batchSql", () -> {
                    int[] r = e.batchSql("INSERT INTO t_acc_user (id, name, age) VALUES (?, ?, ?)",
                            List.of(new Object[]{6, "fred", 50}, new Object[]{7, "gina", 55}));
                    if (r == null || r.length != 2 || r[0] < 1 || r[1] < 1) {
                        throw new HarnessFailure("batchSql 结果异常: " + java.util.Arrays.toString(r));
                    }
                    long n = e.count("SELECT COUNT(*) FROM t_acc_user");
                    if (n != 7) {
                        throw new HarnessFailure("批量后 COUNT 期望 7，实际 " + n);
                    }
                    // 回滚到 5 行，保证后续断言基数一致
                    e.executeSql("DELETE FROM t_acc_user WHERE id > 5");
                    return "len=" + r.length + " count=" + n;
                });
            }

            check(key + ".batchSql(空列表→空数组)", () -> {
                int[] r = e.batchSql("SELECT 1", List.of());
                if (r == null || r.length != 0) {
                    throw new HarnessFailure("空入参应返回空数组");
                }
                return "len=0";
            });

            // ---------- Lambda ----------
            check(key + ".Lambda eq", () -> {
                List<AccUser> list = e.query(AccUser.class).eq(AccUser::getName, "alice").list();
                if (list == null || list.size() != 1) {
                    throw new HarnessFailure("eq(name=alice) 期望 1 条，实际 " + (list == null ? "null" : list.size()));
                }
                return "size=" + list.size();
            });

            check(key + ".Lambda gt+orderByDesc", () -> {
                List<AccUser> list = e.query(AccUser.class).gt(AccUser::getAge, 26).orderByDesc(AccUser::getId).list();
                if (list == null || list.size() != 4) {
                    throw new HarnessFailure("age>26 期望 4 条，实际 " + (list == null ? "null" : list.size()));
                }
                if (list.get(0).getId() != 5L) {
                    throw new HarnessFailure("降序首条期望 id=5，实际 " + list.get(0).getId());
                }
                return "size=" + list.size() + " firstId=" + list.get(0).getId();
            });

            check(key + ".Lambda count", () -> {
                long n = e.count(e.query(AccUser.class).gt(AccUser::getAge, 26));
                if (n != 4) {
                    throw new HarnessFailure("Lambda count 期望 4，实际 " + n);
                }
                return String.valueOf(n);
            });

            check(key + ".Lambda page(1,2)", () -> {
                var page = e.query(AccUser.class).orderByAsc(AccUser::getId).page(1, 2);
                if (page == null || page.getRecords() == null || page.getRecords().size() != 2) {
                    throw new HarnessFailure("Lambda page 期望 2 条，实际 "
                            + (page == null || page.getRecords() == null ? "null" : page.getRecords().size()));
                }
                return "size=" + page.getRecords().size();
            });

            check(key + ".Lambda update", () -> {
                int n = e.update(AccUser.class).set(AccUser::getAge, 99).eq(AccUser::getName, "bob").update();
                if (n != 1) {
                    throw new HarnessFailure("update 期望受影响 1 行，实际 " + n);
                }
                AccUser u = e.queryOne("SELECT id, name, age FROM t_acc_user WHERE name = ?", AccUser.class, "bob");
                if (u == null || u.getAge() == null || u.getAge() != 99) {
                    throw new HarnessFailure("更新后 age 期望 99，实际 " + (u == null ? "null" : u.getAge()));
                }
                // 恢复原值
                e.update(AccUser.class).set(AccUser::getAge, 25).eq(AccUser::getName, "bob").update();
                return "affected=" + n;
            });

            check(key + ".Lambda delete", () -> {
                int n = e.delete(AccUser.class).eq(AccUser::getName, "erin").remove();
                if (n != 1) {
                    throw new HarnessFailure("delete 期望受影响 1 行，实际 " + n);
                }
                long rest = e.count("SELECT COUNT(*) FROM t_acc_user");
                if (rest != 4) {
                    throw new HarnessFailure("删除后 COUNT 期望 4，实际 " + rest);
                }
                // 恢复被删行
                e.store("t_acc_user", List.of(
                        new AccUser(1L, "alice", 30), new AccUser(2L, "bob", 25),
                        new AccUser(3L, "carol", 35), new AccUser(4L, "dave", 40),
                        new AccUser(5L, "erin", 45)));
                return "affected=" + n;
            });

            // ---------- 守卫与错误语义 ----------
            check(key + ".Lambda 全表更新守卫", () -> {
                try {
                    e.update(AccUser.class).set(AccUser::getAge, 1).update();
                    throw new HarnessFailure("无 WHERE 的全表更新未被拒绝");
                } catch (IllegalStateException expected) {
                    return "已拒绝";
                }
            });

            check(key + ".Lambda 全表删除守卫", () -> {
                try {
                    e.delete(AccUser.class).remove();
                    throw new HarnessFailure("无 WHERE 的全表删除未被拒绝");
                } catch (IllegalStateException expected) {
                    return "已拒绝";
                }
            });

            check(key + ".querySql(null) 必须可定位", () -> {
                try {
                    e.querySql((String) null);
                    return "返回(实现自行处理)";
                } catch (NullPointerException npe) {
                    throw new HarnessFailure("抛裸 NullPointerException，调用方无法区分用法错误与内部缺陷");
                } catch (IllegalArgumentException | UnsupportedOperationException ok) {
                    return ok.getClass().getSimpleName();
                } catch (RuntimeException other) {
                    return other.getClass().getSimpleName();
                }
            });

            check(key + ".removeDataSource(null)→IAE", () -> {
                try {
                    e.removeDataSource(null);
                    throw new HarnessFailure("未抛 IllegalArgumentException（接口契约明示名称为 null 时抛出）");
                } catch (IllegalArgumentException expected) {
                    return "已拒绝";
                } catch (UnsupportedOperationException allowed) {
                    return "UnsupportedOperationException(契约允许)";
                }
            });

            if (profile.registry()) {
                check(key + ".数据源注册表", () -> {
                    Set<String> names = e.dataSourceNames();
                    if (names == null || !names.contains("default")) {
                        throw new HarnessFailure("默认数据源未登记: " + names);
                    }
                    if (!e.hasDataSource("default") || e.hasDataSource("no-such")) {
                        throw new HarnessFailure("hasDataSource 判定错误");
                    }
                    return "names=" + names;
                });

                check(key + ".不存在的表→异常(不能静默空)", () -> {
                    try {
                        List<Map<String, Object>> rows = e.querySql("SELECT * FROM t_acc_not_exist");
                        throw new HarnessFailure("未抛异常，返回 " + (rows == null ? "null" : rows.size() + " 行"));
                    } catch (HarnessFailure self) {
                        throw self;
                    } catch (RuntimeException expected) {
                        return expected.getClass().getSimpleName();
                    }
                });
            }
        } finally {
            try {
                e.close();
            } catch (Throwable ignored) {
                // 关闭语义已在阶段一覆盖
            }
        }
    }

    // ---------------- 断言与统计 ----------------

    interface Init {
        Engine create();
    }

    interface Probe {
        String run() throws Exception;
    }

    static void check(String name, Probe probe) {
        try {
            String detail = probe.run();
            pass++;
            emit("  [PASS] " + name + (detail == null || detail.isEmpty() ? "" : "  -> " + detail));
        } catch (Throwable t) {
            recordFail(name + "  -> " + chain(t));
        }
    }

    static void recordFail(String message) {
        fail++;
        FAILURES.add(message);
        emit("  [FAIL] " + message);
    }

    /** 展开异常链（最多 4 层）——只有最内层原因才指向根因。 */
    static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 4) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    /**
     * 统一收尾：打印汇总、以 UTF-8 落盘报告、按失败数退出。
     *
     * @param reportFile 报告文件名（相对当前工作目录）
     */
    static void finishAndExit(String reportFile) {
        emit("=".repeat(78));
        emit("  结果: PASS=" + pass + "  FAIL=" + fail);
        for (String f : FAILURES) {
            emit("    [FAIL] " + f);
        }
        emit("=".repeat(78));
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(reportFile),
                    String.join(System.lineSeparator(), OUT) + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("报告落盘失败: " + e);
        }
        System.exit(fail);
    }

    private static void writeReport() {
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("EngineFunctional-report.txt"),
                    String.join(System.lineSeparator(), OUT) + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("报告落盘失败: " + e);
        }
    }
}
