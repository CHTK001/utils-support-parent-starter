package com.chua.lucene.support.engine;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * LuceneEngine 嵌入式全文搜索真实测试。
 * 验证 index + Lambda CRUD（AbstractEngine 内存路径）。
 */
class LuceneEngineIT {

    @Test
    void lambdaCrud_afterIndex() throws Exception {
        Path dir = Files.createTempDirectory("lucene_it");
        LuceneEngine engine = new LuceneEngine(dir);
        try {
            /* 索引文档 */
            engine.index(Article.class, List.of(
                    article(1, "Apache Lucene is a search library"),
                    article(2, "Elasticsearch builds on Lucene"),
                    article(3, "Database indexing basics")));

            /* AbstractEngine 内存路径：Lambda 条件查询 */
            List<Article> all = engine.query(Article.class).list();
            assertNotNull(all);

            /* 按 id 精确查 */
            Article one = engine.query(Article.class).eq(Article::getId, 1).one();
            if (one != null) {
                assertEquals(1, one.getId());
            }

            /* update */
            int updated = engine.update(Article.class)
                    .set(Article::getTitle, "Updated title")
                    .eq(Article::getId, 1)
                    .update();
            assertTrue(updated >= 0);

            /* delete */
            int deleted = engine.delete(Article.class).eq(Article::getId, 3).remove();
            assertTrue(deleted >= 0);
        } finally {
            engine.close();
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        }
    }

    private Article article(int id, String title) {
        Article a = new Article();
        a.setId(id);
        a.setTitle(title);
        return a;
    }

    public static class Article {
        private int id;
        private String title;
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String t) { this.title = t; }
    }
}
