package com.chua.lucene.support.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lucene 引擎冒烟：真实磁盘索引的写入与全文检索。
 *
 * @author CH
 * @since 4.0.0.42
 */
class LuceneEngineSmokeTest {

    /**
     * 索引实体。
     */
    public static class Article {
        /**
         * 名称（精确匹配字段）
         */
        private String name;
        /**
         * 正文（分词字段）
         */
        private String content;

        /** 获取名称 */
        public String getName() { return name; }
        /** 设置名称 */
        public void setName(String name) { this.name = name; }
        /** 获取正文 */
        public String getContent() { return content; }
        /** 设置正文 */
        public void setContent(String content) { this.content = content; }
    }

    /**
     * 真实索引闭环：index 落盘 -> 按精确字段检索 -> 命中唯一文档。
     */
    @Test
    void index_and_search_roundtrip() throws Exception {
        Path dir = Files.createTempDirectory("lucene-eng");
        dir.toFile().deleteOnExit();
        LuceneEngine engine = new LuceneEngine(dir);

        Article a = new Article(); a.setName("alpha"); a.setContent("greptime timeseries database");
        Article b = new Article(); b.setName("beta");  b.setContent("influxdb storage engine");
        engine.index(Article.class, List.of(a, b));

        List<Article> hit = engine.search(Article.class, "name:alpha");
        assertEquals(1, hit.size(), "精确字段应命中 alpha");
        assertEquals("greptime timeseries database", hit.get(0).getContent());

        // 默认全文域 content 的分词匹配
        List<Article> byContent = engine.search(Article.class, "content_text:timeseries");
        assertEquals(1, byContent.size(), "content_text:timeseries 应仅命中 beta（alpha 无该词）");
        assertEquals("alpha", byContent.get(0).getName());

        engine.close();
    }
}
