package com.chua.ibd.support.innodb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 Java {@code .ibd} 读取器的端到端测试。
 *
 * <p>夹具是<b>真实的 MySQL 8.0 表空间文件</b>（sakila 示例库，BSD 许可，可自由分发），
 * 不是手工拼出来的假页。四张表各自覆盖一类难点：</p>
 * <ul>
 *   <li>{@code actor.ibd} —— 基础类型 + {@code TIMESTAMP}（200 行，1 个二级索引）；</li>
 *   <li>{@code film.ibd} —— {@code ENUM} / {@code SET} / {@code DECIMAL} / 变长文本（1000 行）；</li>
 *   <li>{@code address.ibd} —— {@code GEOMETRY}（25 字节的 SRID + WKB，长度前缀是 1 或 2 字节的经典陷阱）；</li>
 *   <li>{@code staff.ibd} —— 溢出页（LOB）：36 KB 的 PNG 存在 3 个溢出页里，行内只留 20 字节引用。</li>
 * </ul>
 *
 * <p>期望值全部来自真实文件，并已与官方 {@code sakila-mv-data.sql} 逐字节对齐。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class IbdInnoDbReaderTest {

    /**
     * 与 MySQL 客户端一致的口径：{@code TIMESTAMP} 按 +08:00 渲染。
     */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /**
     * 取 {@code src/test/resources/ibd} 下的夹具。
     *
     * @param name 文件名（不含目录）
     * @return 夹具文件
     */
    private static File fixture(String name) {
        java.net.URL url = IbdInnoDbReaderTest.class.getResource("/ibd/" + name);
        if (url == null) {
            throw new IllegalStateException("缺少测试夹具 /ibd/" + name);
        }
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            return new File(url.getPath());
        }
    }

    // ==================== actor：基础类型 ====================

    @Test
    @DisplayName("actor.ibd：表定义、列顺序、主键 root 页都来自 SDI")
    void shouldReadActorDefinition() throws Exception {
        try (IbdTableReader reader = IbdTableReader.open(fixture("actor.ibd"))) {
            IbdTableDefinition definition = reader.definition();

            assertEquals("actor", definition.name());
            assertEquals("sakila", definition.schema());
            assertEquals(List.of("actor_id", "first_name", "last_name", "last_update"),
                    reader.columnNames(), "列顺序是建表顺序，且不能混进 DB_TRX_ID 等系统列");

            IbdIndex clustered = definition.clusteredIndex();
            assertTrue(clustered != null && clustered.primary(), "actor 的聚簇索引就是主键");
            assertEquals(4L, clustered.rootPage(), "PRIMARY 的 root 页号来自 SDI 的 se_private_data");
            assertEquals(IbdIndex.TYPE_PRIMARY, clustered.type());
        }
    }

    @Test
    @DisplayName("actor.ibd：200 行，首行值与 TIMESTAMP 时区渲染")
    void shouldReadAllActorRows() throws Exception {
        try (IbdTableReader reader = IbdTableReader.open(fixture("actor.ibd"), SHANGHAI)) {
            List<Map<String, Object>> rows = reader.readAll();

            assertEquals(200, rows.size());
            Map<String, Object> first = rows.get(0);
            assertEquals(1L, first.get("actor_id"));
            assertEquals("PENELOPE", first.get("first_name"));
            assertEquals("GUINESS", first.get("last_name"));
            assertEquals("2006-02-15 04:34:33", first.get("last_update"),
                    "TIMESTAMP 存的是 UTC 秒，按 +08:00 渲染才与 MySQL 客户端一致");
        }
    }

    // ==================== film：ENUM / SET / DECIMAL ====================

    @Test
    @DisplayName("film.ibd：ENUM / SET / DECIMAL 都按真实字节解出可读值")
    void shouldDecodeFilmEnumsAndDecimals() throws Exception {
        try (IbdTableReader reader = IbdTableReader.open(fixture("film.ibd"), SHANGHAI)) {
            List<Map<String, Object>> rows = reader.readAll();

            assertEquals(1000, rows.size());
            Map<String, Object> first = rows.get(0);
            assertEquals(1L, first.get("film_id"));
            assertEquals("ACADEMY DINOSAUR", first.get("title"));
            assertEquals(2006L, first.get("release_year"));
            assertEquals(new BigDecimal("0.99"), first.get("rental_rate"));
            assertEquals(new BigDecimal("20.99"), first.get("replacement_cost"));
            assertEquals("PG", first.get("rating"), "ENUM 存的是 1 字节序号，不是 4 字节整数");
            assertEquals("Deleted Scenes,Behind the Scenes", first.get("special_features"),
                    "SET 存的是 1 字节位图 0x0C");
            assertNull(first.get("original_language_id"), "该行此列为 NULL，应输出 null 而不是空串");
        }
    }

    // ==================== address：GEOMETRY ====================

    @Test
    @DisplayName("address.ibd：GEOMETRY 输出 SRID + WKB 的十六进制")
    void shouldDecodeGeometry() throws Exception {
        try (IbdTableReader reader = IbdTableReader.open(fixture("address.ibd"), SHANGHAI)) {
            List<Map<String, Object>> rows = reader.readAll();

            assertEquals(603, rows.size());
            assertEquals("0x0000000001010000003e0a325d63345cc0761fdb8d99d94840",
                    rows.get(0).get("location"),
                    "与官方 sakila 数据集里的 /*!50705 0x0000000001010000003E0A325D...*/ 逐字节一致");
        }
    }

    // ==================== staff：溢出页 ====================

    @Test
    @DisplayName("staff.ibd：36 KB 的 PNG 跨 3 个溢出页还原成功")
    void shouldReadOverflowLob() throws Exception {
        try (IbdTableReader reader = IbdTableReader.open(fixture("staff.ibd"), SHANGHAI)) {
            List<Map<String, Object>> rows = reader.readAll();

            assertEquals(2, rows.size());
            String picture = String.valueOf(rows.get(0).get("picture"));
            assertTrue(picture.startsWith("0x89504e470d0a1a0a"), "PNG 魔数，说明溢出页拼接正确");
            assertEquals(36365 * 2 + 2, picture.length(),
                    "长度必须等于行内 20 字节引用里记录的长度（3 个溢出页 15680+16327+4358）");
        }
    }

    @Test
    @DisplayName("staff.ibd：active 列的有符号 TINYINT 符号位被翻转（0x81 -> 1）")
    void shouldDecodeSignedTinyInt() throws Exception {
        try (IbdTableReader reader = IbdTableReader.open(fixture("staff.ibd"), SHANGHAI)) {
            assertEquals(1L, reader.readAll().get(0).get("active"));
        }
    }
}
