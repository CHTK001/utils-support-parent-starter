import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.Endpoint;
import co.elastic.clients.transport.JsonEndpoint;
import co.elastic.clients.transport.TransportOptions;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.datasource.support.annotation.TableName;
import com.chua.elasticsearch.support.engine.ElasticsearchEngine;
import jakarta.json.stream.JsonParser;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 引擎验收运行器 —— 阶段十七：{@code ElasticsearchEngine}（SPI {@code elasticsearch}）。
 *
 * <p>本机没有 ES 服务端，但引擎的 {@code addDataSource} 接受<b>任意</b>
 * {@link ElasticsearchClient}，而该客户端的唯一出站口是 {@link ElasticsearchTransport}
 * —— 一个只有 4 个抽象方法的接口。因此验收里实现一个「回放型假 Transport」：
 * 引擎照常构建 {@code SearchRequest} / {@code BulkRequest}，官方客户端照常序列化请求、
 * 反序列化响应，我们只负责把<b>发出的 URL 与请求体</b>记下来、把<b>固定 JSON</b>喂回去。</p>
 *
 * <p>这样验到的是<b>本项目代码 + 官方客户端的真实序列化/反序列化链路</b>，
 * 而不是「口头声明」：索引名、查询体、分页、计数、实体映射、批量写入、失败语义
 * 全部真实执行。仍未覆盖的是 ES 服务端的查询语义与索引/映射管理。</p>
 *
 * <p>本阶段锁定两个真实缺陷的修复：</p>
 * <ul>
 *   <li>缺陷 18：索引名曾用 {@code getSimpleName().toLowerCase()} 推导，绕过
 *       {@code @TableName} 与驼峰转下划线 —— {@code store("t_acc_user", ...)} 写入的索引
 *       与 {@code query(AccUser.class)} 读取的索引（{@code accuser}）不是同一个；</li>
 *   <li>缺陷 19：{@code query(X).count()} 未覆写，直接抛
 *       {@code UnsupportedOperationException}。</li>
 * </ul>
 */
public final class EngineElasticsearch {

    /** 无 {@code @TableName} 的实体。 */
    public static class PlainUser {
        private Long id;
        private String name;

        public PlainUser() {
        }

        public PlainUser(Long id, String name) {
            this.id = id;
            this.name = name;
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
    }

    private EngineElasticsearch() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段十七：ElasticsearchEngine（回放型假 Transport 驱动真实链路）");
        EngineFunctional.emit("=".repeat(78));

        indexSuite();
        querySuite();
        mapSuite();
        storeSuite();
        errorSuite();
        lifeSuite();

        EngineFunctional.finishAndExit("EngineElasticsearch-report.txt");
    }

    // ==================== 索引名（缺陷 18 回归锁） ====================

    private static void indexSuite() {
        EngineFunctional.emit("--- es.index ---");

        EngineFunctional.check("index.@TableName 生效：请求 URL 为 /t_acc_user/_search", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).list();
                String url = fake.lastUrl();
                if (!url.contains("/t_acc_user/_search")) {
                    throw new EngineFunctional.HarnessFailure(
                            "请求 URL 未使用 @TableName 值，实际=" + url);
                }
                return url;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("index.无 @TableName：驼峰转下划线 /plain_user/_search", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                e.query(PlainUser.class).list();
                String url = fake.lastUrl();
                if (!url.contains("/plain_user/_search")) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望 /plain_user/_search，实际=" + url);
                }
                return url;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("index.不再出现旧的 accuser 索引名（防回退）", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).list();
                String url = fake.lastUrl();
                if (url.contains("accuser")) {
                    throw new EngineFunctional.HarnessFailure(
                            "仍在用 getSimpleName().toLowerCase() 推导索引名: " + url);
                }
                return "no legacy index name";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("index.三个入口（list/one/page）使用同一索引名", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).list();
                String listUrl = fake.lastUrl();
                e.query(EngineFunctional.AccUser.class).one();
                String oneUrl = fake.lastUrl();
                e.query(EngineFunctional.AccUser.class).page(1, 2);
                String pageUrl = fake.lastUrl();
                if (!listUrl.contains("/t_acc_user/") || !oneUrl.contains("/t_acc_user/")
                        || !pageUrl.contains("/t_acc_user/")) {
                    throw new EngineFunctional.HarnessFailure(
                            "三个入口索引名不一致: list=" + listUrl + " one=" + oneUrl + " page=" + pageUrl);
                }
                return "list/one/page -> t_acc_user";
            } finally {
                e.close();
            }
        });
    }

    // ==================== 查询构造 ====================

    private static void querySuite() {
        EngineFunctional.emit("--- es.query ---");

        EngineFunctional.check("query.无条件使用 match_all", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).list();
                String body = fake.lastBody();
                if (!body.contains("match_all")) {
                    throw new EngineFunctional.HarnessFailure("请求体缺少 match_all: " + body);
                }
                return "match_all";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.eq 翻译为 term 查询", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice").list();
                String body = fake.lastBody();
                if (!body.contains("term") || !body.contains("name") || !body.contains("alice")) {
                    throw new EngineFunctional.HarnessFailure("term 查询构造错误: " + body);
                }
                return "term/name/alice";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.one() 下推 size=1", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).one();
                String body = fake.lastBody();
                if (!body.contains("\"size\":1")) {
                    throw new EngineFunctional.HarnessFailure("期望 size=1，实际请求体: " + body);
                }
                return "size=1";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.page(2,3) 下推 from=3 / size=3", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                e.query(EngineFunctional.AccUser.class).page(2, 3);
                String body = fake.lastBody();
                if (!body.contains("\"from\":3") || !body.contains("\"size\":3")) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望 from=3/size=3，实际请求体: " + body);
                }
                return "from=3,size=3";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.page 的 total 取自 hits.total.value（201）", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                var page = e.query(EngineFunctional.AccUser.class).page(1, 2);
                if (page.getTotal() != 201L) {
                    throw new EngineFunctional.HarnessFailure("期望 total=201，实际 " + page.getTotal());
                }
                if (page.getRecords().size() != 2) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望当前页 2 行，实际 " + page.getRecords().size());
                }
                return "total=201, records=2";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.count() 返回 hits.total.value，不再抛 UOE（缺陷 19 回归锁）", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                long n = e.query(EngineFunctional.AccUser.class).count();
                if (n != 201L) {
                    throw new EngineFunctional.HarnessFailure("期望 201，实际 " + n);
                }
                String body = fake.lastBody();
                if (!body.contains("track_total_hits")) {
                    throw new EngineFunctional.HarnessFailure(
                            "count() 未开启 track_total_hits，total 可能被截断: " + body);
                }
                return "count=201";
            } finally {
                e.close();
            }
        });
    }

    // ==================== 响应映射 ====================

    private static void mapSuite() {
        EngineFunctional.emit("--- es.map ---");

        EngineFunctional.check("map.命中行映射为实体（Long/String/Integer）", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                List<EngineFunctional.AccUser> rows = e.query(EngineFunctional.AccUser.class).list();
                if (rows.size() != 2) {
                    throw new EngineFunctional.HarnessFailure("期望 2 行，实际 " + rows.size());
                }
                EngineFunctional.AccUser u = rows.get(0);
                if (!Long.valueOf(1L).equals(u.getId()) || !"alice".equals(u.getName())
                        || !Integer.valueOf(30).equals(u.getAge())) {
                    throw new EngineFunctional.HarnessFailure(
                            "映射错误: id=" + u.getId() + " name=" + u.getName() + " age=" + u.getAge());
                }
                return u.getId() + "/" + u.getName() + "/" + u.getAge();
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("map.缺失字段保持 null，不污染实体", () -> {
            String json = "{\"took\":1,\"timed_out\":false,\"_shards\":{\"total\":1,\"successful\":1,"
                    + "\"skipped\":0,\"failed\":0},\"hits\":{\"total\":{\"value\":1,\"relation\":\"eq\"},"
                    + "\"max_score\":1.0,\"hits\":[{\"_index\":\"t_acc_user\",\"_id\":\"9\","
                    + "\"_score\":1.0,\"_source\":{\"id\":9}}]}}";
            FakeTransport fake = new FakeTransport(json);
            ElasticsearchEngine e = engine(fake);
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
    }

    // ==================== 批量写入 ====================

    private static void storeSuite() {
        EngineFunctional.emit("--- es.store ---");

        EngineFunctional.check("store.空列表不触达客户端", () -> {
            FakeTransport fake = new FakeTransport(BULK_OK_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                e.store("t_acc_user", new ArrayList<>());
                if (!fake.urls.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("空列表仍发起了请求: " + fake.urls);
                }
                return "no-op";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("store._bulk 且每条 _index 为调用方给定的名称", () -> {
            FakeTransport fake = new FakeTransport(BULK_OK_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                List<EngineFunctional.AccUser> rows = new ArrayList<>();
                rows.add(new EngineFunctional.AccUser(1L, "alice", 30));
                rows.add(new EngineFunctional.AccUser(2L, "bob", 25));
                e.store("t_acc_user", rows);
                if (!fake.lastUrl().contains("/_bulk")) {
                    throw new EngineFunctional.HarnessFailure("未走 /_bulk: " + fake.lastUrl());
                }
                // bulk 的请求体是 NDJSON（动作行 + 文档行），不能靠 JSON 反序列化还原；
                // 直接检查官方客户端真正要发送的 BulkRequest 模型。
                var bulk = fake.lastBulk();
                if (bulk == null || bulk.operations().size() != 2) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望 2 条 bulk 操作，实际 " + (bulk == null ? "null" : bulk.operations().size()));
                }
                for (var op : bulk.operations()) {
                    if (!op.isIndex()) {
                        throw new EngineFunctional.HarnessFailure("存在非 index 操作: " + op);
                    }
                    var index = op.index();
                    if (!"t_acc_user".equals(index.index())) {
                        throw new EngineFunctional.HarnessFailure("_index 错误: " + index.index());
                    }
                    if (!(index.document() instanceof EngineFunctional.AccUser doc)) {
                        throw new EngineFunctional.HarnessFailure(
                                "文档类型错误: " + index.document());
                    }
                    if (doc.getId() == null || doc.getName() == null) {
                        throw new EngineFunctional.HarnessFailure("文档内容缺失: " + doc.getName());
                    }
                }
                return "bulk 2 docs -> t_acc_user";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("store.部分失败（errors=true）抛 RuntimeException 且带索引与失败条数", () -> {
            FakeTransport fake = new FakeTransport(BULK_ERROR_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                List<EngineFunctional.AccUser> rows = new ArrayList<>();
                rows.add(new EngineFunctional.AccUser(1L, "alice", 30));
                try {
                    e.store("t_acc_user", rows);
                    throw new EngineFunctional.HarnessFailure("errors=true 却未抛异常");
                } catch (RuntimeException expected) {
                    String msg = expected.getMessage();
                    if (msg == null || !msg.contains("t_acc_user") || !msg.contains("失败条数")) {
                        throw new EngineFunctional.HarnessFailure("异常消息缺少定位信息: " + msg);
                    }
                    return msg;
                }
            } finally {
                e.close();
            }
        });
    }

    // ==================== 失败语义 ====================

    private static void errorSuite() {
        EngineFunctional.emit("--- es.error ---");

        EngineFunctional.check("error.未配置客户端时抛 ISE（而非 NPE/空结果）", () -> {
            ElasticsearchEngine e = new ElasticsearchEngine();
            try {
                e.query(EngineFunctional.AccUser.class).list();
                throw new EngineFunctional.HarnessFailure("未初始化却返回了结果");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("addDataSource")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                return expected.getMessage();
            }
        });

        EngineFunctional.check("error.传输层异常包装为 RuntimeException 且带索引名与 cause", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            fake.failure = new IOException("simulated transport failure");
            ElasticsearchEngine e = engine(fake);
            try {
                try {
                    e.query(EngineFunctional.AccUser.class).list();
                    throw new EngineFunctional.HarnessFailure("传输异常却被吞成空结果");
                } catch (RuntimeException expected) {
                    String msg = expected.getMessage();
                    if (msg == null || !msg.contains("t_acc_user")) {
                        throw new EngineFunctional.HarnessFailure("异常消息缺少索引名: " + msg);
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

        EngineFunctional.check("error.update/delete 明确抛 UOE（不静默失败）", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            try {
                expectUoe(() -> e.update(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice")
                        .set(EngineFunctional.AccUser::getName, "x").update());
                expectUoe(() -> e.delete(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice").remove());
                return "both UOE";
            } finally {
                e.close();
            }
        });
    }

    // ==================== 生命周期与能力声明 ====================

    private static void lifeSuite() {
        EngineFunctional.emit("--- es.life ---");

        EngineFunctional.check("life.close() 不关闭注入的客户端（所有权归调用方）", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            e.close();
            if (fake.closed) {
                throw new EngineFunctional.HarnessFailure(
                        "关闭了注入的客户端/传输层——外部持有的连接被擅自释放");
            }
            return "injected client untouched";
        });

        EngineFunctional.check("life.close() 后查询抛 ISE，且重复 close 不抛", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
            e.close();
            e.close();
            try {
                e.query(EngineFunctional.AccUser.class).list();
                throw new EngineFunctional.HarnessFailure("close() 后仍可查询");
            } catch (IllegalStateException expected) {
                return "closed, then ISE";
            }
        });

        EngineFunctional.check("life.能力声明自洽：无执行器 → supportsSql=false，querySql 抛 UOE", () -> {
            FakeTransport fake = new FakeTransport(SEARCH_JSON);
            ElasticsearchEngine e = engine(fake);
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

    private static ElasticsearchEngine engine(FakeTransport transport) {
        ElasticsearchClient client = new ElasticsearchClient(transport);
        ElasticsearchEngine engine = new ElasticsearchEngine();
        engine.addDataSource("default", new EngineDataSource<ElasticsearchClient>() {
            @Override
            public String name() {
                return "default";
            }

            @Override
            public ElasticsearchClient getSource() {
                return client;
            }

            @Override
            public EngineDataSource<ElasticsearchClient> setSource(Object source) {
                return this;
            }

            @Override
            public Dialect getDialect() {
                return null;
            }

            @Override
            public EngineDataSource<ElasticsearchClient> setDialect(Dialect dialect) {
                return this;
            }

            @Override
            public String url() {
                return "es://fake:9200";
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

    private static final String SEARCH_JSON = "{\"took\":3,\"timed_out\":false,"
            + "\"_shards\":{\"total\":1,\"successful\":1,\"skipped\":0,\"failed\":0},"
            + "\"hits\":{\"total\":{\"value\":201,\"relation\":\"eq\"},\"max_score\":1.0,\"hits\":["
            + "{\"_index\":\"t_acc_user\",\"_id\":\"1\",\"_score\":1.0,"
            + "\"_source\":{\"id\":1,\"name\":\"alice\",\"age\":30}},"
            + "{\"_index\":\"t_acc_user\",\"_id\":\"2\",\"_score\":1.0,"
            + "\"_source\":{\"id\":2,\"name\":\"bob\",\"age\":25}}]}}";

    private static final String BULK_OK_JSON = "{\"took\":5,\"errors\":false,\"items\":["
            + "{\"index\":{\"_index\":\"t_acc_user\",\"_id\":\"1\",\"status\":201}},"
            + "{\"index\":{\"_index\":\"t_acc_user\",\"_id\":\"2\",\"status\":201}}]}";

    private static final String BULK_ERROR_JSON = "{\"took\":5,\"errors\":true,\"items\":["
            + "{\"index\":{\"_index\":\"t_acc_user\",\"_id\":\"1\",\"status\":400,"
            + "\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"bad doc\"}}}]}";

    /**
     * 回放型假 Transport：记录请求 URL 与请求体，返回预置 JSON。
     *
     * <p>刻意<b>不</b>模拟 ES 服务端语义（不解析查询、不做索引管理），
     * 只负责真实地序列化请求、反序列化响应 —— 这样断言聚焦在「本项目引擎发了什么、
     * 又如何解释响应」。</p>
     */
    static final class FakeTransport implements ElasticsearchTransport {
        private final JsonpMapper mapper = new JacksonJsonpMapper();
        private final String responseJson;
        final List<String> urls = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final List<co.elastic.clients.elasticsearch.core.BulkRequest> bulkRequests = new ArrayList<>();
        IOException failure;
        boolean closed;

        FakeTransport(String responseJson) {
            this.responseJson = responseJson;
        }

        String lastUrl() {
            return urls.isEmpty() ? "" : urls.get(urls.size() - 1);
        }

        String lastBody() {
            return bodies.isEmpty() ? "" : bodies.get(bodies.size() - 1);
        }

        co.elastic.clients.elasticsearch.core.BulkRequest lastBulk() {
            return bulkRequests.isEmpty() ? null : bulkRequests.get(bulkRequests.size() - 1);
        }

        @Override
        public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
                RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint,
                TransportOptions options) throws IOException {
            if (failure != null) {
                throw failure;
            }
            urls.add(endpoint.requestUrl(request));
            bodies.add(serializeBody(request, endpoint));
            if (request instanceof co.elastic.clients.elasticsearch.core.BulkRequest bulk) {
                bulkRequests.add(bulk);
            }
            JsonParser parser = mapper.jsonProvider()
                    .createParser(new StringReader(responseJson));
            if (endpoint instanceof JsonEndpoint<RequestT, ResponseT, ErrorT> jsonEndpoint) {
                return jsonEndpoint.responseDeserializer().deserialize(parser, mapper);
            }
            throw new UnsupportedOperationException(
                    "假 Transport 只支持 JSON 端点的反序列化: " + endpoint.id());
        }

        private <RequestT, ResponseT, ErrorT> String serializeBody(
                RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint) {
            try {
                Object body = endpoint.body(request);
                if (body == null) {
                    return "";
                }
                return JsonpUtils.toJsonString(body, mapper);
            } catch (Exception e) {
                return "";
            }
        }

        @Override
        public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(
                RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint,
                TransportOptions options) {
            CompletableFuture<ResponseT> future = new CompletableFuture<>();
            try {
                future.complete(performRequest(request, endpoint, options));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            return future;
        }

        @Override
        public JsonpMapper jsonpMapper() {
            return mapper;
        }

        @Override
        public TransportOptions options() {
            return new FakeOptions();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** 最小可用的 TransportOptions 实现（假 Transport 不需要真实头/参数）。 */
    static final class FakeOptions implements TransportOptions {
        @Override
        public Collection<Map.Entry<String, String>> headers() {
            return List.of();
        }

        @Override
        public Map<String, String> queryParameters() {
            return Map.of();
        }

        @Override
        public Function<List<String>, Boolean> onWarnings() {
            return warnings -> null;
        }

        @Override
        public void updateToken(String token) {
            // 假 Transport 不需要鉴权，忽略
        }

        @Override
        public boolean keepResponseBodyOnException() {
            return false;
        }

        @Override
        public Builder toBuilder() {
            return new FakeOptionsBuilder();
        }
    }

    /** 最小可用的 TransportOptions.Builder。 */
    static final class FakeOptionsBuilder implements TransportOptions.Builder {
        @Override
        public TransportOptions.Builder addHeader(String name, String value) {
            return this;
        }

        @Override
        public TransportOptions.Builder setHeader(String name, String value) {
            return this;
        }

        @Override
        public TransportOptions.Builder removeHeader(String name) {
            return this;
        }

        @Override
        public TransportOptions.Builder setParameter(String name, String value) {
            return this;
        }

        @Override
        public TransportOptions.Builder removeParameter(String name) {
            return this;
        }

        @Override
        public TransportOptions.Builder onWarnings(Function<List<String>, Boolean> handler) {
            return this;
        }

        @Override
        public TransportOptions.Builder keepResponseBodyOnException(boolean value) {
            return this;
        }

        @Override
        public TransportOptions build() {
            return new FakeOptions();
        }
    }
}
