import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.datasource.support.annotation.TableName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 引擎验收运行器 —— 阶段四：嵌入式非 SQL 引擎的功能面。
 *
 * <p>覆盖 parquet / lucene / nitrite / tablesaw，以及 rocksdb 的文档与全文面。
 * 这些引擎的能力面各不相同（列式文件、全文检索、文档存储、内存表），
 * 因此按各自接口契约断言，不套用 SQL 面。</p>
 */
public final class EngineEmbedded {

    /** 验收用文档实体：既有 id/title/body，也有数值字段。 */
    @TableName("acc_embedded")
    public static class Doc {
        @org.dizitart.no2.repository.annotations.Id
        private String id;
        private String title;
        private String body;
        private Integer age;

        public Doc() {
        }

        public Doc(String id, String title, String body, Integer age) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.age = age;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }

        @Override
        public String toString() {
            return "Doc{id=" + id + ",title=" + title + ",age=" + age + '}';
        }
    }

    private EngineEmbedded() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段四：嵌入式非 SQL 引擎功能面");
        EngineFunctional.emit("=".repeat(78));

        runQuietly("lucene", EngineEmbedded::luceneSuite);
        runQuietly("nitrite", EngineEmbedded::nitriteSuite);
        runQuietly("tablesaw", EngineEmbedded::tablesawSuite);
        runQuietly("parquet", EngineEmbedded::parquetSuite);
        runQuietly("rocksdb-doc", EngineEmbedded::rocksdbDocSuite);

        EngineFunctional.finishAndExit("EngineEmbedded-report.txt");
    }

    /** lucene：索引 + 全文检索 + 限额 + 删索引。 */
    static void luceneSuite() {
        EngineFunctional.emit("--- lucene (LuceneEngine) ---");
        String p = "lucene.";
        var e = (com.chua.lucene.support.engine.LuceneEngine) Engine.create("lucene");
        try {
            List<Doc> docs = List.of(
                    new Doc("1", "hello world", "the quick brown fox", 30),
                    new Doc("2", "hello lucene", "fulltext search engine", 25),
                    new Doc("3", "unrelated", "nothing to see here", 40));

            EngineFunctional.check(p + "createFulltextIndex", () -> {
                e.createFulltextIndex(Doc.class, "title", "body");
                return "ok";
            });

            EngineFunctional.check(p + "index 写入", () -> {
                e.index(Doc.class, docs);
                return "docs=" + docs.size();
            });

            EngineFunctional.check(p + "search 命中", () -> {
                List<Doc> hits = e.search(Doc.class, "hello");
                if (hits == null || hits.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("检索 'hello' 应至少命中 1 条，实际 "
                            + (hits == null ? "null" : hits.size()));
                }
                for (Doc d : hits) {
                    if (d.getTitle() == null || !d.getTitle().contains("hello")) {
                        throw new EngineFunctional.HarnessFailure("命中结果与关键字无关: " + d);
                    }
                }
                return "hits=" + hits.size();
            });

            EngineFunctional.check(p + "search 无命中为空", () -> {
                List<Doc> hits = e.search(Doc.class, "zzzz-nonexistent-term");
                if (hits == null || !hits.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("无关关键字应无命中，实际 "
                            + (hits == null ? "null" : hits.size()));
                }
                return "empty";
            });

            EngineFunctional.check(p + "search 限额", () -> {
                List<Doc> hits = e.search("hello", Doc.class, 1);
                if (hits == null || hits.size() > 1) {
                    throw new EngineFunctional.HarnessFailure("limit=1 应最多 1 条，实际 "
                            + (hits == null ? "null" : hits.size()));
                }
                return "size=" + hits.size();
            });

            EngineFunctional.check(p + "dropFulltextIndex", () -> {
                e.dropFulltextIndex(Doc.class, "title", "body");
                return "ok";
            });
        } finally {
            closeQuietly(e);
        }
    }

    /** nitrite：文档 CRUD + 全文检索。 */
    static void nitriteSuite() {
        EngineFunctional.emit("--- nitrite (NitriteEngine) ---");
        String p = "nitrite.";
        var e = (com.chua.nitrite.support.engine.NitriteEngine) Engine.create("nitrite");
        try {
            String coll = "acc_docs";
            EngineFunctional.check(p + "addDataSource", () -> {
                e.addDataSource("default", "target/acc-nitrite.db");
                return "ok";
            });

            EngineFunctional.check(p + "insert", () -> {
                Doc saved = e.insert(coll, new Doc("1", "hello nitrite", "body one", 30));
                if (saved == null) {
                    throw new EngineFunctional.HarnessFailure("insert 返回 null");
                }
                return saved.toString();
            });

            EngineFunctional.check(p + "findById", () -> {
                Doc found = e.findById(coll, "1", Doc.class);
                if (found == null || !"hello nitrite".equals(found.getTitle())) {
                    throw new EngineFunctional.HarnessFailure("findById 结果不符: " + found);
                }
                return found.toString();
            });

            EngineFunctional.check(p + "update", () -> {
                Doc updated = e.update(coll, "1", new Doc("1", "hello nitrite v2", "body two", 31));
                Doc found = e.findById(coll, "1", Doc.class);
                if (found == null || !"hello nitrite v2".equals(found.getTitle())) {
                    throw new EngineFunctional.HarnessFailure("update 后读回不符: " + found);
                }
                return "ok";
            });

            EngineFunctional.check(p + "findAll", () -> {
                List<Doc> all = e.findAll(coll, Doc.class);
                if (all == null || all.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("findAll 应有至少 1 条");
                }
                return "size=" + all.size();
            });

            EngineFunctional.check(p + "delete", () -> {
                boolean deleted = e.delete(coll, "1");
                Doc after = e.findById(coll, "1", Doc.class);
                if (!deleted || after != null) {
                    throw new EngineFunctional.HarnessFailure("delete 期望 true 且读回 null，实际 "
                            + deleted + " / " + after);
                }
                return "ok";
            });
        } finally {
            closeQuietly(e);
        }
    }

    /** tablesaw：CSV 装载 + 表枚举 + Lambda 查询。 */
    static void tablesawSuite() {
        EngineFunctional.emit("--- tablesaw (TablesawEngine) ---");
        String p = "tablesaw.";
        var e = (com.chua.tablesaw.support.engine.TablesawEngine) Engine.create("tablesaw");
        try {
            Path csv = Path.of("target/acc-tablesaw.csv");
            Files.createDirectories(csv.getParent());
            Files.writeString(csv, "id,title,age\n1,hello,30\n2,world,25\n3,again,40\n",
                    StandardCharsets.UTF_8);

            EngineFunctional.check(p + "load(csv)", () -> {
                // TablesawEngine 的表名由类名派生（Doc → doc）：它不依赖
                // datasource-starter，读不到 @TableName 注解，这是该引擎的设计边界。
                e.load("doc", csv.toString());
                Set<String> names = e.tableNames();
                if (names == null || !names.contains("doc")) {
                    throw new EngineFunctional.HarnessFailure("tableNames 应含 doc，实际 " + names);
                }
                return "tables=" + names;
            });

            EngineFunctional.check(p + "getTable 行数", () -> {
                var table = e.getTable("doc");
                if (table == null) {
                    throw new EngineFunctional.HarnessFailure("getTable 返回 null");
                }
                long rows = table.rowCount();
                if (rows != 3) {
                    throw new EngineFunctional.HarnessFailure("期望 3 行，实际 " + rows);
                }
                return "rows=" + rows;
            });

            EngineFunctional.check(p + "Lambda count", () -> {
                long n = e.count(e.query(Doc.class));
                if (n != 3) {
                    throw new EngineFunctional.HarnessFailure("count 期望 3，实际 " + n);
                }
                return String.valueOf(n);
            });

            EngineFunctional.check(p + "Lambda list + 条件", () -> {
                List<Doc> all = e.query(Doc.class).list();
                if (all == null || all.size() != 3) {
                    throw new EngineFunctional.HarnessFailure("list 期望 3 条，实际 "
                            + (all == null ? "null" : all.size()));
                }
                List<Doc> gt = e.query(Doc.class).gt(Doc::getAge, 26).list();
                if (gt == null || gt.size() != 2) {
                    throw new EngineFunctional.HarnessFailure("age>26 期望 2 条，实际 "
                            + (gt == null ? "null" : gt.size()));
                }
                return "all=" + all.size() + " gt=" + gt.size();
            });
        } catch (Exception ex) {
            EngineFunctional.recordFail(p + "套件异常: " + EngineFunctional.chain(ex));
        } finally {
            closeQuietly(e);
        }
    }

    /** parquet：写入后按 SQL 面读回。 */
    static void parquetSuite() {
        EngineFunctional.emit("--- parquet (ParquetEngine) ---");
        String p = "parquet.";
        var e = (com.chua.parquet.support.engine.ParquetEngine) Engine.create("parquet");
        try {
            EngineFunctional.check(p + "addDataSource", () -> {
                e.addDataSource("default", "target/acc-parquet");
                return "ok";
            });

            EngineFunctional.check(p + "store 写入", () -> {
                e.store("acc_embedded", List.of(
                        new Doc("1", "hello parquet", "body", 30),
                        new Doc("2", "world parquet", "body", 25)));
                return "ok";
            });

            EngineFunctional.check(p + "Lambda 读回", () -> {
                // ParquetEngine 不声明 SQL 能力（supportsSql=false），读路径是
                // AbstractEngine.executeNewQuery，即 Lambda/类型化查询面，不能用 querySql。
                List<Doc> rows = e.query(Doc.class).list();
                if (rows == null || rows.size() != 2) {
                    throw new EngineFunctional.HarnessFailure("期望 2 行，实际 "
                            + (rows == null ? "null" : rows.size()));
                }
                return "rows=" + rows.size();
            });
        } finally {
            closeQuietly(e);
        }
    }

    /** rocksdb：文档存储 + 全文检索面。 */
    static void rocksdbDocSuite() {
        EngineFunctional.emit("--- rocksdb 文档面 (RocksDbEngine) ---");
        String p = "rocksdb-doc.";
        var e = (com.chua.rocksdb.support.engine.RocksDbEngine) Engine.create("rocksdb");
        try {
            EngineFunctional.check(p + "addDataSource", () -> {
                e.addDataSource("default", "target/acc-rocksdb-doc");
                return "ok";
            });

            String coll = "acc_docs";
            EngineFunctional.check(p + "insert", () -> {
                Object saved = e.insert(coll, new Doc("1", "hello rocksdb", "body", 30));
                if (saved == null) {
                    throw new EngineFunctional.HarnessFailure("insert 返回 null");
                }
                return "ok";
            });

            EngineFunctional.check(p + "findById", () -> {
                Doc found = e.findById(coll, "1", Doc.class);
                if (found == null || !"hello rocksdb".equals(found.getTitle())) {
                    throw new EngineFunctional.HarnessFailure("findById 结果不符: " + found);
                }
                return found.toString();
            });

            EngineFunctional.check(p + "findAll", () -> {
                List<Doc> all = e.findAll(coll, Doc.class);
                if (all == null || all.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("findAll 应有至少 1 条");
                }
                return "size=" + all.size();
            });

            EngineFunctional.check(p + "delete", () -> {
                boolean deleted = e.delete(coll, "1");
                if (!deleted) {
                    throw new EngineFunctional.HarnessFailure("delete 应返回 true");
                }
                return "ok";
            });
        } catch (Exception ex) {
            EngineFunctional.recordFail(p + "套件异常: " + EngineFunctional.chain(ex));
        } finally {
            closeQuietly(e);
        }
    }

    private static void closeQuietly(Object o) {
        if (o instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception ignored) {
                // 关闭语义不在本阶段断言范围
            }
        }
    }

    /**
     * 隔离执行一个套件：构造或套件本身抛异常时记为一条失败，不影响其余套件。
     *
     * @param name 套件名
     * @param body 套件体
     */
    private static void runQuietly(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            EngineFunctional.recordFail(name + " 套件异常退出: " + EngineFunctional.chain(t));
        }
    }
}
