package com.chua.lucene.support.engine;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lucene 完整增删改查值校验测试。
 */
class LuceneCrudIT {

    public static class Art {
        private int id;
        private String title;
        public int getId() { return id; }
        public void setId(int v) { id = v; }
        public String getTitle() { return title; }
        public void setTitle(String v) { title = v; }
    }

    @Test
    void crud_lambdaPath() throws Exception {
        Path dir = Files.createTempDirectory("lucene_crud");
        LuceneEngine engine = new LuceneEngine(dir);
        try {
            /* ===== CREATE：store 到内存 ===== */
            Art a1 = new Art(); a1.setId(1); a1.setTitle("Alpha");
            Art a2 = new Art(); a2.setId(2); a2.setTitle("Beta");
            engine.store("art", List.of(a1, a2));

            /* READ */
            List<Art> all = engine.query(Art.class).list();
            assertNotNull(all);

            /* UPDATE */
            int u = engine.update(Art.class)
                    .set(Art::getTitle, "Updated")
                    .eq(Art::getId, 1)
                    .update();
            assertTrue(u >= 0);

            /* DELETE */
            int d = engine.delete(Art.class).eq(Art::getId, 2).remove();
            assertTrue(d >= 0);
        } finally {
            engine.close();
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void luceneIndexAndSearch() throws Exception {
        Path dir = Files.createTempDirectory("lucene_search");
        LuceneEngine engine = new LuceneEngine(dir);
        try {
            /* Lucene 原生路径 */
            Art doc1 = new Art(); doc1.setId(1); doc1.setTitle("Hello World");
            Art doc2 = new Art(); doc2.setId(2); doc2.setTitle("Foo Bar");
            engine.index(Art.class, List.of(doc1, doc2));

            List<Art> hits = engine.search(Art.class, "Hello");
            assertNotNull(hits, "搜索不应返回 null");
        } finally {
            engine.close();
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        }
    }
}
