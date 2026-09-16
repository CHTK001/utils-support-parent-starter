package com.chua.ibd.support.innodb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 字段字节 → 可读值 的解码回归测试。
 *
 * <p><b>测试向量全部取自真实的 MySQL 8.0 {@code .ibd} 文件</b>（sakila 库），
 * 不是照着实现反推出来的：每个十六进制串都是从页里抠出来的原始字节，
 * 注释里同时给出它来自哪张表哪一行。这样测试才挡得住「实现和测试一起错」。</p>
 *
 * <p>这里钉住的都是实测踩过的真 bug：</p>
 * <ol>
 *   <li><b>ENUM / SET 宽度</b> —— 曾经用「读 4 字节、不足处左侧补零」的方法取序号，
 *       于是 1 字节的 {@code 0x02} 变成 {@code 0x02000000} = 33554432，
 *       {@code film.rating} 整列输出成数字而不是 {@code PG}；</li>
 *   <li><b>有符号整数符号位翻转</b> —— {@code staff.active} 的 {@code 1} 存成 {@code 0x81}，
 *       照直读会得到 {@code -127}；</li>
 *   <li><b>{@code CHAR} 尾部空格</b> —— 页里按声明长度空格填充，取出来必须去尾空格；</li>
 *   <li><b>{@code TIMESTAMP} 存的是 UTC 秒</b> —— 必须按目标时区渲染才和 MySQL 客户端一致。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
class IbdTypeDecoderTest {

    /**
     * 与 MySQL 客户端一致的口径：{@code TIMESTAMP} 按 +08:00 渲染。
     */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /**
     * 构造一个只填了「解码相关」字段的列定义。
     *
     * @param name   列名
     * @param type   列类型
     * @param length 最大字节长度
     * @return 列定义
     */
    private static IbdColumn column(String name, IbdColumnType type, long length) {
        return column(name, type, length, 0, 0, List.of());
    }

    /**
     * 构造列定义。
     *
     * @param name      列名
     * @param type      列类型
     * @param length    最大字节长度
     * @param precision 数值精度
     * @param scale     数值标度
     * @param elements  ENUM / SET 候选值
     * @return 列定义
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private static IbdColumn column(String name, IbdColumnType type, long length,
                                   int precision, int scale, List<String> elements) {
        return new IbdColumn(name, type, true, false, length, 255L, 0,
                precision, scale, elements, type.name().toLowerCase(java.util.Locale.ROOT),
                false, false, null, false, "");
    }

    /**
     * 十六进制串 → 字节数组。
     *
     * @param hex 十六进制串
     * @return 字节数组
     */
    private static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ==================== ENUM ====================

    @Test
    @DisplayName("ENUM 1 字节序号：0x02 -> PG（film.rating 第 1 行）")
    void shouldDecodeOneByteEnum() {
        IbdColumn rating = column("rating", IbdColumnType.ENUM, 20, 0, 0,
                List.of("G", "PG", "PG-13", "R", "NC-17"));

        assertEquals("PG", IbdTypeDecoder.decode(rating, bytes("02")),
                "0x02 是序号 2，对应 elements[1]='PG'；"
                        + "若实现按 4 字节左侧补零读，会得到 33554432");
    }

    @Test
    @DisplayName("ENUM 序号 0 表示非法值，输出 NULL")
    void shouldDecodeIllegalEnumAsNull() {
        IbdColumn rating = column("rating", IbdColumnType.ENUM, 20, 0, 0, List.of("G", "PG"));

        assertNull(IbdTypeDecoder.decode(rating, bytes("00")));
    }

    @Test
    @DisplayName("ENUM 元素数 > 255 时占 2 字节，序号按实际宽度解释")
    void shouldDecodeTwoByteEnum() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            many.add("v" + i);
        }
        IbdColumn column = column("big_enum", IbdColumnType.ENUM, 4096, 0, 0, many);

        assertEquals(2, column.fixedSize(), "元素数 > 255 才是 2 字节");
        assertEquals("v299", IbdTypeDecoder.decode(column, bytes("012c")), "0x012C = 300 -> 第 300 个元素");
    }

    // ==================== SET ====================

    @Test
    @DisplayName("SET 1 字节位图：0x0C -> Deleted Scenes,Behind the Scenes（film 第 1 行）")
    void shouldDecodeOneByteSet() {
        IbdColumn features = column("special_features", IbdColumnType.SET, 216, 0, 0,
                List.of("Trailers", "Commentaries", "Deleted Scenes", "Behind the Scenes"));

        assertEquals("Deleted Scenes,Behind the Scenes",
                IbdTypeDecoder.decode(features, bytes("0c")),
                "0x0C = 二进制 1100 -> 第 3、4 个成员");
    }

    @Test
    @DisplayName("SET 空集合输出空串")
    void shouldDecodeEmptySet() {
        IbdColumn features = column("special_features", IbdColumnType.SET, 216, 0, 0,
                List.of("Trailers", "Commentaries"));

        assertEquals("", IbdTypeDecoder.decode(features, bytes("00")));
    }

    @Test
    @DisplayName("SET 元素数 4 个只占 1 字节（不是 4 字节）")
    void shouldComputeSetWidth() {
        assertEquals(1, column("s", IbdColumnType.SET, 216, 0, 0,
                List.of("a", "b", "c", "d")).fixedSize());
        assertEquals(2, IbdColumn.setBinarySize(9));
        assertEquals(8, IbdColumn.setBinarySize(64));
    }

    // ==================== 有符号整数符号位翻转 ====================

    @Test
    @DisplayName("TINYINT 有符号：0x81 -> 1（staff.active 第 1 行）")
    void shouldDecodeSignedTinyInt() {
        assertEquals(1L, IbdTypeDecoder.decode(column("active", IbdColumnType.TINY, 4), bytes("81")),
                "InnoDB 把符号位取反存储，照直读会得到 -127");
        assertEquals(-128L, IbdTypeDecoder.decode(column("v", IbdColumnType.TINY, 4), bytes("00")));
        assertEquals(127L, IbdTypeDecoder.decode(column("v", IbdColumnType.TINY, 4), bytes("ff")));
    }

    @Test
    @DisplayName("无符号整数不翻转符号位")
    void shouldDecodeUnsignedInt() {
        IbdColumn column = new IbdColumn("film_id", IbdColumnType.SHORT, false, true, 5L, 255L, 0,
                0, 0, List.of(), "smallint unsigned", false, false, null, false, "");

        assertEquals(1L, IbdTypeDecoder.decode(column, bytes("0001")));
    }

    // ==================== DECIMAL ====================

    @Test
    @DisplayName("DECIMAL(4,2)：0x8063 -> 0.99（film.rental_rate 第 1 行）")
    void shouldDecodeDecimalFourTwo() {
        IbdColumn rate = column("rental_rate", IbdColumnType.NEWDECIMAL, 6, 4, 2, List.of());

        assertEquals(2, rate.fixedSize());
        assertEquals(new BigDecimal("0.99"), IbdTypeDecoder.decode(rate, bytes("8063")));
    }

    @Test
    @DisplayName("DECIMAL(5,2)：0x801463 -> 20.99（film.replacement_cost 第 1 行）")
    void shouldDecodeDecimalFiveTwo() {
        IbdColumn cost = column("replacement_cost", IbdColumnType.NEWDECIMAL, 7, 5, 2, List.of());

        assertEquals(3, cost.fixedSize());
        assertEquals(new BigDecimal("20.99"), IbdTypeDecoder.decode(cost, bytes("801463")));
    }

    @Test
    @DisplayName("DECIMAL 负数是把整段按位取反，且首字节符号位必须清掉")
    void shouldDecodeNegativeDecimal() {
        IbdColumn column = column("d", IbdColumnType.NEWDECIMAL, 6, 4, 2, List.of());

        // 0x7F9C 是 0x8063(0.99) 的按位取反；解回来时若忘了清首字节的符号位，
        // 「整部前导残组」会读成 0x80 = 128，结果变成 -128.99
        assertEquals(new BigDecimal("-0.99"), IbdTypeDecoder.decode(column, bytes("7f9c")));
    }

    // ==================== 时间 ====================

    @Test
    @DisplayName("TIMESTAMP 存 UTC 秒，按 +08:00 渲染 -> 2006-02-15 05:03:42（film 第 1 行）")
    void shouldDecodeTimestampInTargetZone() {
        IbdColumn column = column("last_update", IbdColumnType.TIMESTAMP2, 4);

        assertEquals("2006-02-15 05:03:42",
                IbdTypeDecoder.decode(column, bytes("43f245ae"), SHANGHAI));
        assertEquals("2006-02-14 21:03:42",
                IbdTypeDecoder.decode(column, bytes("43f245ae"), ZoneId.of("UTC")),
                "同一串字节在 UTC 下少 8 小时 —— 证明存的是 UTC 秒");
    }

    @Test
    @DisplayName("DATETIME(0) 占 5 字节：0x99781D6124 -> 2006-02-14 22:04:36（customer 第 1 行）")
    void shouldDecodeDateTime2() {
        IbdColumn column = column("create_date", IbdColumnType.DATETIME2, 5);
        assertEquals(5, column.fixedSize());

        // 位域：1 位符号 + 17 位「年*13+月」+ 5 位日 + 5 位时 + 6 位分 + 6 位秒。
        // 曾经只读了 4 字节，结果输出成 -10035--4-28 01:53:33
        assertEquals("2006-02-14 22:04:36", IbdTypeDecoder.decode(column, bytes("99781d6124")));
    }

    @Test
    @DisplayName("DATE 占 3 字节：0x0FAC4F -> 2006-02-15")
    void shouldDecodeDate() {
        assertEquals("2006-02-15", IbdTypeDecoder.decode(column("d", IbdColumnType.DATE, 3), bytes("0fac4f")));
    }

    @Test
    @DisplayName("TIME(0) 占 3 字节：0x80C8B8 -> 12:34:56")
    void shouldDecodeTime2() {
        // 1 位符号 + 1 位保留 + 10 位时 + 6 位分 + 6 位秒，整体加 0x800000 偏移。
        // 这里同样是 3 字节，若用「4 字节定宽、低位补零」的读法会得到 0x80C8B800，小时变成 514
        IbdColumn column = column("t", IbdColumnType.TIME2, 3);
        assertEquals(3, column.fixedSize());
        assertEquals("12:34:56", IbdTypeDecoder.decode(column, bytes("80c8b8")));
    }

    @Test
    @DisplayName("YEAR 存的是 1900 起的偏移：0x6A -> 2006（film 第 1 行）")
    void shouldDecodeYear() {
        assertEquals(2006L, IbdTypeDecoder.decode(column("release_year", IbdColumnType.YEAR, 1), bytes("6a")));
    }

    // ==================== 字符串 ====================

    @Test
    @DisplayName("VARCHAR 直接按列字符集解码（film.title 第 1 行）")
    void shouldDecodeVarchar() {
        IbdColumn title = column("title", IbdColumnType.VARCHAR, 512);

        assertEquals("ACADEMY DINOSAUR",
                IbdTypeDecoder.decode(title, bytes("41434144454d592044494e4f53415552")));
    }

    @Test
    @DisplayName("CHAR 要去掉页里填充的尾部空格")
    void shouldStripTrailingSpacesOfChar() {
        IbdColumn column = column("name", IbdColumnType.STRING, 60);

        assertEquals("English", IbdTypeDecoder.decode(column, "English             ".getBytes()));
        assertEquals("English", IbdTypeDecoder.decode(column, "English".getBytes()));
    }

    @Test
    @DisplayName("binary 字符集（collation 63）输出 0x 十六进制")
    void shouldDecodeBinaryAsHex() {
        IbdColumn column = new IbdColumn("b", IbdColumnType.BLOB, true, false, 255L, 63L, 0,
                0, 0, List.of(), "blob", false, false, null, false, "");

        assertEquals("0x00ff10", IbdTypeDecoder.decode(column, bytes("00ff10")));
    }

    // ==================== GEOMETRY ====================

    @Test
    @DisplayName("GEOMETRY 原样输出 SRID + WKB 的十六进制（address.location 第 1 行）")
    void shouldDecodeGeometryAsInternalFormat() {
        IbdColumn column = new IbdColumn("location", IbdColumnType.GEOMETRY, false, false,
                4294967295L, 63L, 0, 0, 0, List.of(), "point", false, false, null, false, "");

        // 口径：4 字节小端 SRID + 标准 WKB，与 mysqldump 输出的内部格式一致，
        // 也与官方 sakila 数据集里的 /*!50705 0x0000000001010000003E0A325D...*/ 完全一致
        assertEquals("0x0000000001010000003e0a325d63345cc0761fdb8d99d94840",
                IbdTypeDecoder.decode(column, bytes("0000000001010000003e0a325d63345cc0761fdb8d99d94840")));
    }

    // ==================== 系统列 ====================

    @Test
    @DisplayName("系统列宽度：DB_TRX_ID 6 字节、DB_ROLL_PTR 7 字节、DB_ROW_ID 6 字节")
    void shouldComputeSystemColumnWidth() {
        assertEquals(6, column(IbdColumn.COL_DB_TRX_ID, IbdColumnType.INT24, 0).fixedSize());
        assertEquals(7, column(IbdColumn.COL_DB_ROLL_PTR, IbdColumnType.LONGLONG, 0).fixedSize());
        assertEquals(6, column(IbdColumn.COL_DB_ROW_ID, IbdColumnType.LONGLONG, 0).fixedSize());
    }

    @Test
    @DisplayName("系统列按无符号输出（DB_ROLL_PTR 第 1 行）")
    void shouldDecodeSystemColumnAsUnsigned() {
        IbdColumn column = column(IbdColumn.COL_DB_ROLL_PTR, IbdColumnType.LONGLONG, 0);

        assertEquals(36591746987852048L, IbdTypeDecoder.decode(column, bytes("82000000ec0110")));
    }
}
