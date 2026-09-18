package com.chua.elasticsearch.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Elasticsearch 引擎集成测试。
 *
 * <p>基于 Testcontainers：自动拉起 {@code docker.elastic.co/elasticsearch/elasticsearch:8.12.0}
 * 容器（单节点、无安全认证），无需外部服务与环境变量。</p>
 *
 * <p>测试覆盖：bulk 存储、match_all 全量、eq/gt/like/in 条件翻译、
 * one/page、以及 update/delete 空实现（恒返回 0）的验证。
 * {@code store} 时索引名必须与查询索引名（实体类名小写 {@code esdoc}）一致。</p>
 *
 * <p>需要本机 Docker 环境；容器随测试类启动、结束后自动回收。</p>
 *
 * @author CH
 */
public class ElasticsearchEngineIntegrationTest {

    private static final String INDEX_NAME = "esdoc";

    /** 真实 Elasticsearch 8 容器（禁用安全） */
    static final ElasticsearchContainer ES = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.12.0")
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node")
            .waitingFor(Wait.forHttp("/_cluster/health").forStatusCode(200));

    private ElasticsearchEngine engine;

    /**
     * 启动Container。
     */
    @BeforeAll
    static void startContainer() {
        ES.start();
    }

    /**
     * 停止Container。
     */
    @AfterAll
    static void stopContainer() {
        ES.stop();
    }

    /**
     * 设置Up。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @BeforeEach
    void setUp() throws Exception {
        engine = new ElasticsearchEngine();
        engine.addDataSource("default", new EsDataSource("http://" + ES.getHost() + ":" + ES.getMappedPort(9200)));

        // 清理旧索引（不存在时忽略异常）
        try {
            engine.getClient().indices().delete(d -> d.index(INDEX_NAME));
        } catch (Exception ignored) {
        }

        // 批量写入 4 条文档
        List<EsDoc> docs = List.of(
                new EsDoc(1L, "Elasticsearch 入门", "第一章 基础概念"),
                new EsDoc(2L, "Java 并发编程", "第二章 线程池"),
                new EsDoc(3L, "分布式系统设计", "第三章 一致性"),
                new EsDoc(4L, "搜索引擎原理", "第四章 倒排索引")
        );
        engine.store(INDEX_NAME, docs);
        // bulk 写入后强制刷新，保证可立即检索
        engine.getClient().indices().refresh(r -> r.index(INDEX_NAME));
    }

    /** 测试用 ES 数据源（匿名实现 EngineDataSource） */
    private static class EsDataSource implements EngineDataSource<Object> {

        private final String url;

        EsDataSource(String url) {
            this.url = url;
        }

        @Override
        public String name() {
            return "default";
        }

        @Override
        public Object getSource() {
            return url;
        }

        @Override
        public EngineDataSource<Object> setSource(Object source) {
            return this;
        }

        @Override
        public Dialect getDialect() {
            return null;
        }

        @Override
        public EngineDataSource<Object> setDialect(Dialect dialect) {
            return this;
        }

        @Override
        public String url() {
            return url;
        }

        @Override
        public String username() {
            return null;
        }

        @Override
        public String password() {
            return null;
        }
    }

    // ==================== getExecutor 返回 null ====================

    /**
     * 测试：获取ExecutorReturnsNull。
     */
    @Test
    void testGetExecutorReturnsNull() {
        assertNull(engine.getExecutor(), "ElasticsearchEngine.getExecutor() 应返回 null");
    }

    /**
     * 测试：获取客户端NotNull。
     */
    @Test
    void testGetClientNotNull() {
        assertNotNull(engine.getClient(), "客户端应已初始化");
    }

    // ==================== list / query ====================

    /**
     * 测试：列出全部。
     */
    @Test
    void testListAll() {
        List<EsDoc> all = engine.query(EsDoc.class).list();
        assertEquals(4, all.size(), "应有 4 条文档");
        assertTrue(all.stream().allMatch(d -> d.getId() != null), "id 非空");
        assertTrue(all.stream().allMatch(d -> d.getTitle() != null), "title 非空");
    }

    /**
     * 测试：查询WithEqCondition。
     */
    @Test
    void testQueryWithEqCondition() {
        List<EsDoc> list = engine.query(EsDoc.class)
                .eq(EsDoc::getTitle, "Java 并发编程")
                .list();
        assertEquals(1, list.size());
        assertEquals("Java 并发编程", list.getFirst().getTitle());
        assertEquals(2L, list.getFirst().getId());
    }

    /**
     * 测试：查询WithGtCondition。
     */
    @Test
    void testQueryWithGtCondition() {
        List<EsDoc> list = engine.query(EsDoc.class)
                .gt(EsDoc::getId, 2L)
                .list();
        assertEquals(2, list.size(), "id > 2 应有 3、4 两条");
    }

    /**
     * 测试：查询WithLikeCondition。
     */
    @Test
    void testQueryWithLikeCondition() {
        // LIKE → wildcard，% 转为 *，命中含 "引擎" 的标题
        List<EsDoc> list = engine.query(EsDoc.class)
                .like(EsDoc::getTitle, "%引擎%")
                .list();
        assertFalse(list.isEmpty(), "应命中含「引擎」的文档");
        assertTrue(list.stream().anyMatch(d -> d.getTitle().contains("引擎")));
    }

    /**
     * 测试：查询WithInCondition。
     */
    @Test
    void testQueryWithInCondition() {
        List<EsDoc> list = engine.query(EsDoc.class)
                .in(EsDoc::getId, List.of(1L, 3L))
                .list();
        assertEquals(2, list.size());
        List<Long> ids = list.stream().map(EsDoc::getId).toList();
        assertTrue(ids.contains(1L));
        assertTrue(ids.contains(3L));
    }

    // ==================== one ====================

    /**
     * 测试：One。
     */
    @Test
    void testOne() {
        EsDoc doc = engine.query(EsDoc.class)
                .eq(EsDoc::getId, 3L)
                .one();
        assertNotNull(doc);
        assertEquals("分布式系统设计", doc.getTitle());
    }

    /**
     * 测试：OneReturnsNullWhen编号Match。
     */
    @Test
    void testOneReturnsNullWhenNoMatch() {
        EsDoc doc = engine.query(EsDoc.class)
                .eq(EsDoc::getId, 999L)
                .one();
        assertNull(doc);
    }

    // ==================== page ====================

    /**
     * 测试：页。
     */
    @Test
    void testPage() {
        var page = engine.query(EsDoc.class).page(1, 2);
        assertNotNull(page);
        assertEquals(2, page.getRecords().size(), "第一页应返回 2 条");
        assertEquals(4L, page.getTotal(), "内存分页 total 应为 4");
    }

    // ==================== update / delete 空实现 ====================

    /**
     * 测试：更新是否编号操作。
     */
    @Test
    void testUpdateIsNoOp() {
        int affected = engine.update(EsDoc.class)
                .set(EsDoc::getTitle, "改标题")
                .eq(EsDoc::getId, 1L)
                .update();
        assertEquals(0, affected, "ElasticsearchEngine.update() 为空实现，恒返回 0");
    }

    /**
     * 测试：删除是否编号操作。
     */
    @Test
    void testDeleteIsNoOp() {
        int affected = engine.delete(EsDoc.class)
                .eq(EsDoc::getId, 1L)
                .remove();
        assertEquals(0, affected, "ElasticsearchEngine.delete() 为空实现，恒返回 0");
    }

    // ==================== 空结果 ====================

    /**
     * 测试：Empty结果。
     */
    @Test
    void testEmptyResult() {
        List<EsDoc> list = engine.query(EsDoc.class)
                .eq(EsDoc::getTitle, "不存在的标题")
                .list();
        assertTrue(list.isEmpty(), "无匹配时应返回空列表");
    }

    // ==================== close ====================

    /**
     * 测试：关闭。
     */
    @Test
    void testClose() {
        assertDoesNotThrow(engine::close);
    }
}
