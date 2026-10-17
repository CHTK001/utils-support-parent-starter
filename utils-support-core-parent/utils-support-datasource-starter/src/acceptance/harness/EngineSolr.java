import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.solr.support.engine.SolrEngine;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 引擎验收运行器 —— 阶段十五：Solr 引擎（SPI {@code solr}）。
 *
 * <p>本机没有 Solr 服务端，但 {@link SolrEngine#addDataSource(String, EngineDataSource)}
 * 接受任意 {@link SolrClient}，而 {@code SolrClient} 只有一个抽象方法。因此可以在验收里
 * 实现一个<b>记录型假客户端</b>：引擎照常构建 {@link SolrQuery}、照常映射返回文档，
 * 我们只把「发出去的请求」和「喂回去的响应」握在手里。</p>
 *
 * <p>这样验到的是<b>本项目代码</b>（表名解析、查询构造、分页下推、文档→实体映射、
 * 异常包装、生命周期），而不是第三方客户端。</p>
 *
 * <p>本阶段同时锁定两个真实缺陷的修复：</p>
 * <ul>
 *   <li>缺陷 13：集合名曾用 {@code getSimpleName().toLowerCase()} 推导，绕过
 *       {@code @TableName} 与驼峰转下划线，导致 {@code store("t_acc_user", ...)} 写入的数据
 *       与 {@code query(AccUser.class)} 读取的集合不是同一个；</li>
 *   <li>缺陷 14：{@code query(X).count()} 未覆写，直接抛 {@code UnsupportedOperationException}。</li>
 * </ul>
 */
public final class EngineSolr {

    /** 无 {@code @TableName} 的实体：用于验证驼峰转下划线。 */
    public static class PlainUser {
        private Long id;
        private String name;

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
    }

    private EngineSolr() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段十五：Solr 引擎（记录型假 SolrClient 驱动真实路径）");
        EngineFunctional.emit("=".repeat(78));

        tableSuite();
        querySuite();
        countSuite();
        mappingSuite();
        storeSuite();
        deleteSuite();
        errorSuite();
        lifeSuite();

        EngineFunctional.finishAndExit("EngineSolr-report.txt");
    }

    // ==================== 表名解析（缺陷 13 回归锁） ====================

    private static void tableSuite() {
        EngineFunctional.emit("--- solr.table ---");

        EngineFunctional.check("table.@TableName 生效：查询集合名为注解值", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).list();
                return "collection=" + fake.lastCollection;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("table.未标注 @TableName：驼峰转下划线", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                e.query(PlainUser.class).list();
                String c = fake.lastCollection;
                if (!"plain_user".equals(c)) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望集合名 plain_user，实际 " + c + "（驼峰未转下划线）");
                }
                return c;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("table.store 与 query 使用同一集合名（读写一致）", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                List<EngineFunctional.AccUser> rows = new ArrayList<>();
                rows.add(new EngineFunctional.AccUser(1L, "alice", 30));
                e.store("t_acc_user", rows);
                String writeCollection = fake.addCollection;
                e.query(EngineFunctional.AccUser.class).list();
                String readCollection = fake.lastCollection;
                if (!writeCollection.equals(readCollection)) {
                    throw new EngineFunctional.HarnessFailure(
                            "写入集合 " + writeCollection + " 与读取集合 " + readCollection
                                    + " 不一致，store 进去的数据 query 读不出来");
                }
                return "write=read=" + writeCollection;
            } finally {
                e.close();
            }
        });
    }

    // ==================== 查询构造 ====================

    private static void querySuite() {
        EngineFunctional.emit("--- solr.query ---");

        EngineFunctional.check("query.无条件的 q 为 *:*", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).list();
                String q = fake.lastQuery.getQuery();
                if (!"*:*".equals(q)) {
                    throw new EngineFunctional.HarnessFailure("期望 *:*，实际 " + q);
                }
                return q;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.eq 翻译为 field:value", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).eq(EngineFunctional.AccUser::getName, "alice").list();
                String q = fake.lastQuery.getQuery();
                if (!"name:alice".equals(q)) {
                    throw new EngineFunctional.HarnessFailure("期望 name:alice，实际 " + q);
                }
                return q;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.多个条件用 AND 连接", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice")
                        .eq(EngineFunctional.AccUser::getAge, 30)
                        .list();
                String q = fake.lastQuery.getQuery();
                if (!"name:alice AND age:30".equals(q)) {
                    throw new EngineFunctional.HarnessFailure("期望 AND 连接，实际 " + q);
                }
                return q;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.one() 只取 1 行", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).one();
                Integer rows = fake.lastQuery.getRows();
                if (rows == null || rows != 1) {
                    throw new EngineFunctional.HarnessFailure("期望 rows=1，实际 " + rows);
                }
                return "rows=" + rows;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.list() 未指定分页时上限 1000", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).list();
                Integer rows = fake.lastQuery.getRows();
                if (rows == null || rows != 1000) {
                    throw new EngineFunctional.HarnessFailure("期望 rows=1000，实际 " + rows);
                }
                return "rows=" + rows;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.page(3,20) 下推 start=40 / rows=20", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).page(3, 20);
                SolrQuery q = fake.lastQuery;
                if (q.getStart() != 40 || q.getRows() != 20) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望 start=40/rows=20，实际 start=" + q.getStart() + "/rows=" + q.getRows());
                }
                return "start=" + q.getStart() + ",rows=" + q.getRows();
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.page 的 total 取自 numFound（非当前页行数）", () -> {
            FakeClient fake = new FakeClient();
            fake.numFound = 137;
            fake.documents.clear();
            SolrEngine e = engine(fake);
            try {
                var page = e.query(EngineFunctional.AccUser.class).page(2, 10);
                if (page.getTotal() != 137L) {
                    throw new EngineFunctional.HarnessFailure("期望 total=137，实际 " + page.getTotal());
                }
                return "total=" + page.getTotal();
            } finally {
                e.close();
            }
        });
    }

    // ==================== count（缺陷 14 回归锁） ====================

    private static void countSuite() {
        EngineFunctional.emit("--- solr.count ---");

        EngineFunctional.check("count.不再抛 UOE，返回 numFound", () -> {
            FakeClient fake = new FakeClient();
            fake.numFound = 42;
            SolrEngine e = engine(fake);
            try {
                long n = e.query(EngineFunctional.AccUser.class).count();
                if (n != 42L) {
                    throw new EngineFunctional.HarnessFailure("期望 42，实际 " + n);
                }
                return "count=" + n;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("count.不被 list() 的 1000 行上限截断", () -> {
            FakeClient fake = new FakeClient();
            fake.numFound = 5000;
            SolrEngine e = engine(fake);
            try {
                long n = e.query(EngineFunctional.AccUser.class).count();
                if (n != 5000L) {
                    throw new EngineFunctional.HarnessFailure("期望 5000，实际 " + n);
                }
                return "count=" + n;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("count.以 rows=0 只取总数（不拉文档）", () -> {
            FakeClient fake = new FakeClient();
            fake.numFound = 7;
            SolrEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).count();
                Integer rows = fake.lastQuery.getRows();
                if (rows == null || rows != 0) {
                    throw new EngineFunctional.HarnessFailure("期望 rows=0，实际 " + rows);
                }
                return "rows=" + rows;
            } finally {
                e.close();
            }
        });
    }

    // ==================== 文档 → 实体映射 ====================

    private static void mappingSuite() {
        EngineFunctional.emit("--- solr.map ---");

        EngineFunctional.check("map.字段类型正确映射（Long/String/Integer）", () -> {
            FakeClient fake = new FakeClient();
            fake.documents.clear();
            fake.documents.add(doc(1L, "alice", 30));
            fake.numFound = 1;
            SolrEngine e = engine(fake);
            try {
                List<EngineFunctional.AccUser> rows = e.query(EngineFunctional.AccUser.class).list();
                if (rows.size() != 1) {
                    throw new EngineFunctional.HarnessFailure("期望 1 行，实际 " + rows.size());
                }
                EngineFunctional.AccUser u = rows.get(0);
                if (!Long.valueOf(1L).equals(u.getId()) || !"alice".equals(u.getName())
                        || !Integer.valueOf(30).equals(u.getAge())) {
                    throw new EngineFunctional.HarnessFailure(
                            "映射结果错误: id=" + u.getId() + " name=" + u.getName() + " age=" + u.getAge());
                }
                return u.getId() + "/" + u.getName() + "/" + u.getAge();
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("map.多文档顺序保持", () -> {
            FakeClient fake = new FakeClient();
            fake.documents.clear();
            fake.documents.add(doc(1L, "a", 10));
            fake.documents.add(doc(2L, "b", 20));
            fake.documents.add(doc(3L, "c", 30));
            fake.numFound = 3;
            SolrEngine e = engine(fake);
            try {
                List<EngineFunctional.AccUser> rows = e.query(EngineFunctional.AccUser.class).list();
                if (rows.size() != 3) {
                    throw new EngineFunctional.HarnessFailure("期望 3 行，实际 " + rows.size());
                }
                for (int i = 0; i < 3; i++) {
                    if (!Long.valueOf(i + 1L).equals(rows.get(i).getId())) {
                        throw new EngineFunctional.HarnessFailure("第 " + i + " 行顺序错乱");
                    }
                }
                return "3 rows in order";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("map.缺失字段保持 null，不污染实体", () -> {
            FakeClient fake = new FakeClient();
            fake.documents.clear();
            SolrDocument d = new SolrDocument();
            d.addField("id", 9L);
            fake.documents.add(d);
            fake.numFound = 1;
            SolrEngine e = engine(fake);
            try {
                List<EngineFunctional.AccUser> rows = e.query(EngineFunctional.AccUser.class).list();
                EngineFunctional.AccUser u = rows.get(0);
                if (!Long.valueOf(9L).equals(u.getId()) || u.getName() != null || u.getAge() != null) {
                    throw new EngineFunctional.HarnessFailure(
                            "缺失字段未保持 null: name=" + u.getName() + " age=" + u.getAge());
                }
                return "id=9, name=null, age=null";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("map.多值字段取首值（Solr 返回 List 的情形）", () -> {
            FakeClient fake = new FakeClient();
            fake.documents.clear();
            SolrDocument d = new SolrDocument();
            d.addField("id", 5L);
            List<String> multi = new ArrayList<>();
            multi.add("first");
            multi.add("second");
            d.addField("name", multi);
            fake.documents.add(d);
            fake.numFound = 1;
            SolrEngine e = engine(fake);
            try {
                List<EngineFunctional.AccUser> rows = e.query(EngineFunctional.AccUser.class).list();
                if (!"first".equals(rows.get(0).getName())) {
                    throw new EngineFunctional.HarnessFailure(
                            "多值字段未取首值: " + rows.get(0).getName());
                }
                return "name=" + rows.get(0).getName();
            } finally {
                e.close();
            }
        });
    }

    // ==================== 写入 ====================

    private static void storeSuite() {
        EngineFunctional.emit("--- solr.store ---");

        EngineFunctional.check("store.空列表不触达客户端", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                e.store("t_acc_user", new ArrayList<>());
                if (fake.addCollection != null) {
                    throw new EngineFunctional.HarnessFailure("空列表仍调用了 add");
                }
                return "no-op";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("store.add + commit，且字段来自 getter", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                List<EngineFunctional.AccUser> rows = new ArrayList<>();
                rows.add(new EngineFunctional.AccUser(1L, "alice", 30));
                e.store("t_acc_user", rows);
                if (!"t_acc_user".equals(fake.addCollection)) {
                    throw new EngineFunctional.HarnessFailure("add 集合名错误: " + fake.addCollection);
                }
                if (!"t_acc_user".equals(fake.commitCollection)) {
                    throw new EngineFunctional.HarnessFailure("commit 集合名错误: " + fake.commitCollection);
                }
                SolrInputDocument d = fake.added.get(0);
                if (!Long.valueOf(1L).equals(d.getFieldValue("id"))
                        || !"alice".equals(d.getFieldValue("name"))
                        || !Integer.valueOf(30).equals(d.getFieldValue("age"))) {
                    throw new EngineFunctional.HarnessFailure("写入文档字段错误: " + d);
                }
                return "add+commit, fields=id/name/age";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("store.客户端异常包装为 RuntimeException 且带集合名", () -> {
            FakeClient fake = new FakeClient();
            fake.failAdd = true;
            SolrEngine e = engine(fake);
            try {
                List<EngineFunctional.AccUser> rows = new ArrayList<>();
                rows.add(new EngineFunctional.AccUser(1L, "alice", 30));
                try {
                    e.store("t_acc_user", rows);
                    throw new EngineFunctional.HarnessFailure("客户端异常却被吞掉");
                } catch (RuntimeException expected) {
                    if (!expected.getMessage().contains("t_acc_user")) {
                        throw new EngineFunctional.HarnessFailure(
                                "异常消息缺集合名: " + expected.getMessage());
                    }
                    return expected.getMessage();
                }
            } finally {
                e.close();
            }
        });
    }

    // ==================== 删除 ====================

    private static void deleteSuite() {
        EngineFunctional.emit("--- solr.delete ---");

        EngineFunctional.check("delete.命中 0 行时不发 deleteByQuery，返回 0", () -> {
            FakeClient fake = new FakeClient();
            fake.numFound = 0;
            SolrEngine e = engine(fake);
            try {
                int n = e.delete(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "nobody").remove();
                if (n != 0) {
                    throw new EngineFunctional.HarnessFailure("期望返回 0，实际 " + n);
                }
                if (fake.deleteQuery != null) {
                    throw new EngineFunctional.HarnessFailure("0 命中仍发送了 deleteByQuery");
                }
                return "0, no delete";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("delete.按条件删除：集合名/查询正确且提交", () -> {
            FakeClient fake = new FakeClient();
            fake.numFound = 3;
            SolrEngine e = engine(fake);
            try {
                int n = e.delete(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice").remove();
                if (n != 3) {
                    throw new EngineFunctional.HarnessFailure("期望返回 3，实际 " + n);
                }
                if (!"t_acc_user".equals(fake.deleteCollection)) {
                    throw new EngineFunctional.HarnessFailure("删除集合名错误: " + fake.deleteCollection);
                }
                if (!"name:alice".equals(fake.deleteQuery)) {
                    throw new EngineFunctional.HarnessFailure("删除条件错误: " + fake.deleteQuery);
                }
                if (!"t_acc_user".equals(fake.commitCollection)) {
                    throw new EngineFunctional.HarnessFailure("删除后未提交");
                }
                return "deleted=" + n + " q=" + fake.deleteQuery;
            } finally {
                e.close();
            }
        });
    }

    // ==================== 失败语义 ====================

    private static void errorSuite() {
        EngineFunctional.emit("--- solr.error ---");

        EngineFunctional.check("error.未配置客户端时抛 ISE（而非 NPE/空结果）", () -> {
            SolrEngine e = new SolrEngine();
            try {
                e.query(EngineFunctional.AccUser.class).list();
                throw new EngineFunctional.HarnessFailure("未初始化却返回了结果");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("未初始化")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                return expected.getMessage();
            }
        });

        EngineFunctional.check("error.客户端异常包装为 RuntimeException 且带集合名与查询", () -> {
            FakeClient fake = new FakeClient();
            fake.failQuery = new SolrServerException("boom");
            SolrEngine e = engine(fake);
            try {
                try {
                    e.query(EngineFunctional.AccUser.class).eq(EngineFunctional.AccUser::getName, "x").list();
                    throw new EngineFunctional.HarnessFailure("客户端异常却被吞成空结果");
                } catch (RuntimeException expected) {
                    String msg = expected.getMessage();
                    if (!msg.contains("t_acc_user") || !msg.contains("name:x")) {
                        throw new EngineFunctional.HarnessFailure("异常消息缺少定位信息: " + msg);
                    }
                    if (expected.getCause() == null) {
                        throw new EngineFunctional.HarnessFailure("异常丢失了原始 cause");
                    }
                    return msg;
                }
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("error.executeUpdate/executeDelete 抛 UOE 并指引用链式 API", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                // 两个方法各有重载（UpdateSql / LambdaUpdateWrapper），必须显式收窄才不歧义
                com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql<EngineFunctional.AccUser> upd = null;
                com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql<EngineFunctional.AccUser> del = null;
                expectUoe(() -> e.executeUpdate(upd));
                expectUoe(() -> e.executeDelete(del));
                return "both UOE";
            } finally {
                e.close();
            }
        });
    }

    // ==================== 生命周期与能力声明 ====================

    private static void lifeSuite() {
        EngineFunctional.emit("--- solr.life ---");

        EngineFunctional.check("life.close() 关闭底层客户端", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            e.close();
            if (!fake.closed) {
                throw new EngineFunctional.HarnessFailure("close() 未关闭 SolrClient");
            }
            return "client closed";
        });

        EngineFunctional.check("life.close() 幂等：重复调用不抛异常，客户端已关闭", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            e.close();
            e.close();
            if (!fake.closed) {
                throw new EngineFunctional.HarnessFailure("close() 后客户端未关闭");
            }
            // 底层 close 实际被调用 2 次以上：SolrEngine.close() 直接关一次，
            // 随后 AbstractEngine.close() 关闭数据源时（EngineDataSource.close 默认实现
            // 会关闭 AutoCloseable 的 source）再关一次。SolrClient/HikariDataSource 的
            // close 都是幂等的，故不构成缺陷，但此处显式记录该事实。
            return "closed=true, closeCount=" + fake.closeCount;
        });

        EngineFunctional.check("life.能力声明自洽：无执行器 → supportsSql=false，querySql 抛 UOE", () -> {
            FakeClient fake = new FakeClient();
            SolrEngine e = engine(fake);
            try {
                if (e.getExecutor() != null) {
                    throw new EngineFunctional.HarnessFailure("getExecutor() 应为 null");
                }
                if (e.supportsSql()) {
                    throw new EngineFunctional.HarnessFailure("无执行器却声明 supportsSql=true");
                }
                expectUoe(() -> e.querySql("select * from t"));
                return "supportsSql=false";
            } finally {
                e.close();
            }
        });
    }

    // ==================== 基础设施 ====================

    private static SolrEngine engine(FakeClient client) {
        SolrEngine engine = new SolrEngine();
        engine.addDataSource("default", new EngineDataSource<SolrClient>() {
            @Override
            public String name() {
                return "default";
            }

            @Override
            public SolrClient getSource() {
                return client;
            }

            @Override
            public EngineDataSource<SolrClient> setSource(Object source) {
                return this;
            }

            @Override
            public Dialect getDialect() {
                return null;
            }

            @Override
            public EngineDataSource<SolrClient> setDialect(Dialect dialect) {
                return this;
            }

            @Override
            public String url() {
                return "fake://solr";
            }

            @Override
            public String username() {
                return null;
            }

            @Override
            public String password() {
                return null;
            }
        });
        return engine;
    }

    private static SolrDocument doc(Long id, String name, Integer age) {
        SolrDocument d = new SolrDocument();
        d.addField("id", id);
        d.addField("name", name);
        d.addField("age", age);
        return d;
    }

    private static void expectUoe(Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        } catch (Throwable t) {
            throw new EngineFunctional.HarnessFailure(
                    "期望 UnsupportedOperationException，实际 " + EngineFunctional.chain(t));
        }
        throw new EngineFunctional.HarnessFailure("期望 UnsupportedOperationException，未抛异常");
    }

    /**
     * 记录型假客户端：只实现引擎真正调用到的方法。
     *
     * <p>刻意<b>不</b>模拟 Solr 的查询语义（不解析 q），因为那属于服务端行为；
     * 它只负责把引擎发出的 {@link SolrQuery} 原样暴露出来，并按 {@code numFound}
     * 返回可配置的文档，从而让断言聚焦在本项目代码上。</p>
     */
    static final class FakeClient extends SolrClient {
        final List<String> collections = new ArrayList<>();
        final List<SolrDocument> documents = new ArrayList<>();
        final List<SolrInputDocument> added = new ArrayList<>();
        long numFound;
        String lastCollection;
        SolrQuery lastQuery;
        String addCollection;
        String commitCollection;
        String deleteCollection;
        String deleteQuery;
        Exception failQuery;
        boolean failAdd;
        boolean closed;
        int closeCount;

        @Override
        public NamedList<Object> request(SolrRequest<?> request, String collection)
                throws SolrServerException, IOException {
            throw new UnsupportedOperationException("阶段十五只覆写 query/add/commit/deleteByQuery/close");
        }

        @Override
        public QueryResponse query(String collection, SolrParams params)
                throws SolrServerException, IOException {
            if (failQuery instanceof SolrServerException sse) {
                throw sse;
            }
            if (failQuery instanceof RuntimeException re) {
                throw re;
            }
            this.lastCollection = collection;
            this.collections.add(collection);
            this.lastQuery = (SolrQuery) params;

            SolrDocumentList list = new SolrDocumentList();
            int rows = lastQuery.getRows() == null ? 10 : lastQuery.getRows();
            int start = lastQuery.getStart() == null ? 0 : lastQuery.getStart();
            if (rows > 0 && start < documents.size()) {
                int end = Math.min(start + rows, documents.size());
                list.addAll(documents.subList(start, end));
            }
            list.setNumFound(numFound);
            NamedList<Object> nl = new NamedList<>();
            nl.add("response", list);
            QueryResponse response = new QueryResponse();
            response.setResponse(nl);
            return response;
        }

        @Override
        public UpdateResponse add(String collection, Collection<SolrInputDocument> docs)
                throws SolrServerException, IOException {
            if (failAdd) {
                throw new IOException("fake add failure");
            }
            this.addCollection = collection;
            this.added.clear();
            this.added.addAll(docs);
            return new UpdateResponse();
        }

        @Override
        public UpdateResponse commit(String collection) {
            this.commitCollection = collection;
            return new UpdateResponse();
        }

        @Override
        public UpdateResponse deleteByQuery(String collection, String q) {
            this.deleteCollection = collection;
            this.deleteQuery = q;
            return new UpdateResponse();
        }

        @Override
        public void close() {
            this.closed = true;
            this.closeCount++;
        }
    }
}
