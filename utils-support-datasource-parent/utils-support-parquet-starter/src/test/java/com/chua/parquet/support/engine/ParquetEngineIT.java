package com.chua.parquet.support.engine;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ParquetEngine 列式存储真实测试。
 */
class ParquetEngineIT {

    public static class PRecord implements java.io.Serializable {
        private int id;
        private String name;
        private double score;
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public double getScore() { return score; }
        public void setScore(double s) { this.score = s; }
    }

    @Test
    void storeAndQueryRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("parquet_it");
        ParquetEngine engine = new ParquetEngine();
        engine.addDataSource("pq", dir.toString());
        try {
            List<PRecord> data = List.of(
                    rec(1, "Alice", 90.5),
                    rec(2, "Bob", 85.0),
                    rec(3, "Cathy", 78.2));

            engine.store("precs", data);

            /* 查询回来 */
            List<PRecord> result = engine.query(PRecord.class)
                    .eq(PRecord::getName, "Alice")
                    .list();
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals(90.5, result.get(0).getScore(), 0.01);
        } finally {
            engine.close();
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        }
    }

    private PRecord rec(int id, String name, double score) {
        PRecord r = new PRecord();
        r.setId(id); r.setName(name); r.setScore(score);
        return r;
    }
}
