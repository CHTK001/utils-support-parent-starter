package com.chua.nitrite.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import org.dizitart.no2.repository.annotations.Id;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nitrite 内嵌库引擎冒烟：真实文件库的插入/全文检索/删除闭环。
 *
 * @author CH
 * @since 4.0.0.42
 */
class NitriteEngineSmokeTest {

    /**
     * 文档实体。
     */
    public static class Doc {
        /**
         * 文档 ID
         */
        @Id
        private String id;
        /**
         * 正文
         */
        private String text;

        /** 获取 ID */
        public String getId() { return id; }
        /** 设置 ID */
        public void setId(String id) { this.id = id; }
        /** 获取正文 */
        public String getText() { return text; }
        /** 设置正文 */
        public void setText(String text) { this.text = text; }
    }

    /**
     * SPI 创建可用。
     */
    @Test
    void spi_create_works() {
        assertNotNull(Engine.create("nitrite"));
    }

    /**
     * 真实内嵌文件库：insert(Document) -> findAll -> findById -> delete 闭环。
     */
    @Test
    void insert_findAll_delete_roundtrip() throws Exception {
        File dbFile = Files.createTempFile("nitrite-smoke", ".db").toFile();
        dbFile.deleteOnExit();
        NitriteEngine engine = (NitriteEngine) Engine.create("nitrite");
        try {
            engine.addDataSource("default", dbFile.getAbsolutePath());

            org.dizitart.no2.collection.Document d1 =
                    org.dizitart.no2.collection.Document.createDocument("id", "1");
            d1.put("text", "hello greptime world");
            org.dizitart.no2.collection.Document d2 =
                    org.dizitart.no2.collection.Document.createDocument("id", "2");
            d2.put("text", "influxdb hbase parquet");
            engine.insert("docs", d1);
            engine.insert("docs", d2);

            List<org.dizitart.no2.collection.Document> all =
                    engine.findAll("docs", org.dizitart.no2.collection.Document.class);
            assertEquals(2, all.size(), "findAll 应读回真实插入的两条文档");

            // 真实删除
            assertTrue(engine.delete("docs", "1"));
            assertNull(engine.findById("docs", "1",
                    org.dizitart.no2.collection.Document.class));
        } finally {
            engine.close();
        }
    }
}
