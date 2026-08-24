package com.chua.parquet.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parquet 引擎测试：真实文件落盘 -> 查询/更新/删除闭环（本地，无外部服务）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class ParquetEngineTest {

    /**
     * 存储实体（measurement: demo_row）。
     */
    public static class DemoRow {
        /**
         * 名称列
         */
        private String name;
        /**
         * 数值列
         */
        private double value;
        /**
         * 序号列
         */
        private long ts;

        /** 获取名称 */
        public String getName() { return name; }
        /** 设置名称 */
        public void setName(String name) { this.name = name; }
        /** 获取数值 */
        public double getValue() { return value; }
        /** 设置数值 */
        public void setValue(double value) { this.value = value; }
        /** 获取序号 */
        public long getTs() { return ts; }
        /** 设置序号 */
        public void setTs(long ts) { this.ts = ts; }
    }

    /**
     * SPI 键可用。
     */
    @Test
    void spi_create_works() {
        assertNotNull(Engine.create("parquet"));
    }

    /**
     * 真实文件闭环：store 落盘 -> query 读文件过滤 -> update 改写文件 -> delete 删行重写。
     */
    @Test
    void file_roundtrip_store_query_update_delete() throws Exception {
        File dir = Files.createTempDirectory("pq-engine-test").toFile();
        dir.deleteOnExit();
        ParquetEngine engine = (ParquetEngine) Engine.create("parquet");
        engine.addDataSource("default", dir.getAbsolutePath());

        // store：真实写入 demo_row.parquet
        DemoRow a = new DemoRow(); a.setName("a"); a.setValue(1.5); a.setTs(1);
        DemoRow b = new DemoRow(); b.setName("b"); b.setValue(2.5); b.setTs(2);
        engine.store("demo_row", List.of(a, b));

        File parquet = new File(dir, "demo_row.parquet");
        assertTrue(parquet.exists() && parquet.length() > 0, "Parquet 文件应真实存在");

        // query：从真实文件读回并条件过滤
        List<DemoRow> hit = engine.query(DemoRow.class)
                .eq(DemoRow::getName, "b")
                .list();
        assertEquals(1, hit.size());
        assertEquals(2.5, hit.get(0).getValue(), 1e-9);

        // update：命中 1 行并写回文件
        int updated = engine.update(DemoRow.class)
                .set(DemoRow::getValue, 9.9)
                .eq(DemoRow::getName, "a")
                .update();
        assertEquals(1, updated);
        List<DemoRow> afterUpdate = engine.query(DemoRow.class)
                .eq(DemoRow::getName, "a")
                .list();
        assertEquals(9.9, afterUpdate.get(0).getValue(), 1e-9, "更新应写回真实文件");

        // delete：命中 1 行，文件重写后仅剩 1 行
        int removed = engine.delete(DemoRow.class)
                .eq(DemoRow::getName, "b")
                .remove();
        assertEquals(1, removed, "删除应返回真实影响行数");
        List<DemoRow> rest = engine.query(DemoRow.class).list();
        assertEquals(1, rest.size());
        assertEquals("a", rest.get(0).getName());

        // 排序 + 分页走父类通用逻辑（数据源为真实文件）
        DemoRow c = new DemoRow(); c.setName("c"); c.setValue(0.5); c.setTs(3);
        List<DemoRow> more = new java.util.ArrayList<>(rest);
        more.add(c);
        engine.store("demo_row", more);
        var page = engine.query(DemoRow.class).orderByDesc(DemoRow::getTs).page(1, 2);
        assertEquals(2, page.getTotal());
        assertEquals(2, page.getRecords().size());
    }
}
