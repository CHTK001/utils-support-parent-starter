package com.chua.excel.support.export;

import com.chua.common.support.export.Engines;
import com.chua.common.support.export.ExportException;
import com.chua.common.support.export.ExportResult;
import com.chua.common.support.export.Documents;
import com.chua.common.support.export.annotation.ExportColumn;
import com.chua.common.support.export.annotation.ExportSheet;
import com.chua.common.support.export.meta.ColumnType;
import com.chua.common.support.export.meta.HorizontalAlign;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 端到端验收测试 —— 接口一生成 xlsx、接口二填充 xlsx 模板。
 *
 * @author CH
 * @since 4.0.0.42
 */
class ExcelExportAcceptanceTest {

    /**
     * 验收数据行。
     */
    @ExportSheet(name = "订单", title = "订单月报", headerRow = 2, freezeHeader = true, autoFilter = true)
    public static class OrderVo {

        /**
         * 订单号。
         */
        @ExportColumn(name = "订单号", order = 1, width = 22)
        private String orderNo;

        /**
         * 金额。
         */
        @ExportColumn(name = "金额", order = 2, type = ColumnType.AMOUNT, prefix = "¥", align = HorizontalAlign.RIGHT)
        private BigDecimal amount;

        /**
         * 状态。
         */
        @ExportColumn(name = "状态", order = 3, type = ColumnType.ENUM, enumNames = {"0=未支付", "1=已支付"})
        private Integer status;

        /**
         * 下单日期。
         */
        @ExportColumn(name = "下单日期", order = 4, type = ColumnType.DATE, dateFormat = "yyyy/MM/dd")
        private LocalDate orderDate;

        /**
         * 内部字段，不应出现。
         */
        @ExportColumn(ignore = true)
        private String traceId;

        /**
         * 构造订单。
         *
         * @param orderNo 订单号
         * @param amount  金额
         * @param status  状态
         * @param date    日期
         * @return 订单
         */
        public static OrderVo of(String orderNo, String amount, int status, String date) {
            OrderVo vo = new OrderVo();
            vo.orderNo = orderNo;
            vo.amount = new BigDecimal(amount);
            vo.status = status;
            vo.orderDate = LocalDate.parse(date);
            vo.traceId = "trace-" + orderNo;
            return vo;
        }
    }

    /**
     * 构造样例数据。
     *
     * @param count 条数
     * @return 订单列表
     */
    private static List<OrderVo> sample(int count) {
        List<OrderVo> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(OrderVo.of("SO-" + (i + 1), i + ".5", i % 2, "2024-0" + ((i % 9) + 1) + "-15"));
        }
        return list;
    }

    /**
     * 在已关闭的工作簿上下文中执行断言。
     *
     * @param data   xlsx 字节
     * @param action 断言逻辑
     * @throws IOException 读取异常
     */
    private static void withWorkbook(byte[] data, ThrowingConsumer<Workbook> action) throws IOException {
        try (InputStream in = new ByteArrayInputStream(data);
             Workbook workbook = new XSSFWorkbook(in)) {
            action.accept(workbook);
        }
    }

    /**
     * 允许抛出受检异常的消费者。
     */
    @FunctionalInterface
    private interface ThrowingConsumer<T> {

        /**
         * 消费。
         *
         * @param value 值
         * @throws IOException 读取异常
         */
        void accept(T value) throws IOException;
    }

    /**
     * 读取单元格文本。
     *
     * @param row   行
     * @param index 列下标
     * @return 文本
     */
    private static String text(Row row, int index) {
        Cell cell = row.getCell(index);
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    @Test
    @DisplayName("接口一：注解解析 → 生成 xlsx，标题/表头/数据/忽略列全部正确")
    void generateFromList() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportResult result = Documents.of(sample(3))
                .sheet("订单表")
                .output(out)
                .export();

        assertEquals("xlsx", result.format());
        assertEquals("poi", result.engine());
        assertEquals(3, result.rows());
        assertFalse(result.templated());
        assertTrue(result.data().length > 0);

        withWorkbook(result.data(), workbook -> {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("订单表", sheet.getSheetName());
            assertEquals("订单月报", text(sheet.getRow(0), 0), "headerRow=2 时首行为标题");
            assertEquals("订单号", text(sheet.getRow(1), 0));
            assertEquals("金额", text(sheet.getRow(1), 1));
            assertEquals("状态", text(sheet.getRow(1), 2));
            assertEquals("下单日期", text(sheet.getRow(1), 3));
            assertEquals(4, sheet.getRow(1).getLastCellNum(), "ignore=true 的列不应生成");

            assertEquals("SO-1", text(sheet.getRow(2), 0));
            assertEquals("未支付", text(sheet.getRow(2), 2), "ENUM 应按 enumNames 映射");
            assertEquals("已支付", text(sheet.getRow(3), 2));
            assertEquals("2024/01/15", text(sheet.getRow(2), 3), "DATE 应按 dateFormat 格式化");

            assertNotNull(sheet.getPaneInformation(), "freezeHeader 应生效");
            assertTrue(((org.apache.poi.xssf.usermodel.XSSFSheet) sheet).getCTWorksheet().isSetAutoFilter(),
                    "autoFilter 应生效");
            assertTrue(sheet.getColumnWidth(0) > 0, "列宽应被设置");
        });
    }

    @Test
    @DisplayName("接口一：带 prefix 的 AMOUNT 列渲染为文本前缀金额")
    void amountPrefix() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = Documents.of(sample(1))
                .columns("amount")
                .header(false)
                .title(null)
                .output(out)
                .export()
                .data();
        withWorkbook(data, workbook -> {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals(1, sheet.getRow(0).getLastCellNum());
            assertEquals("¥0.50", text(sheet.getRow(0), 0));
        });
    }

    @Test
    @DisplayName("链式配置：title(null) 显式清空注解声明的标题，空串则表示未声明")
    void titleOverrideSentinel() throws IOException {
        assertTrue(Documents.of(sample(1)).title("月报").preview().getTitle().equals("月报"));
        assertTrue(Documents.of(sample(1)).title("").preview().getTitle().equals("订单月报"),
                "空串应保留注解声明的标题");
        assertTrue(Documents.of(sample(1)).title(null).preview().getTitle().isEmpty(),
                "null 应显式清空标题");
    }

    @Test
    @DisplayName("接口一：rowsPerSheet 超限时自动拆分工作表")
    void splitSheets() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = Documents.of(sample(5))
                .sheet("订单")
                .rowsPerSheet(2)
                .output(out)
                .export()
                .data();
        try (InputStream in = new ByteArrayInputStream(data);
             Workbook workbook = new XSSFWorkbook(in)) {
            assertEquals(3, workbook.getNumberOfSheets());
            assertEquals("订单_1", workbook.getSheetName(0));
            assertEquals("订单_2", workbook.getSheetName(1));
            assertEquals("订单_3", workbook.getSheetName(2));
            assertEquals("SO-1", text(workbook.getSheetAt(0).getRow(2), 0));
            assertEquals("SO-5", text(workbook.getSheetAt(2).getRow(2), 0));
        }
    }

    @Test
    @DisplayName("接口一：Map 行数据无需 POJO 即可导出")
    void mapRows() throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "甲");
        row.put("age", 20);
        row.put("birthday", LocalDate.of(2024, 3, 1));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = Documents.of(List.of(row))
                .sheet("Map")
                .headerRow(1)
                .output(out)
                .export()
                .data();
        withWorkbook(data, workbook -> {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("Name", text(sheet.getRow(0), 0));
            assertEquals("Age", text(sheet.getRow(0), 1));
            assertEquals("Birthday", text(sheet.getRow(0), 2));
            assertEquals("甲", text(sheet.getRow(1), 0));
            assertEquals("20.0", text(sheet.getRow(1), 1));
            assertEquals("2024-03-01", text(sheet.getRow(1), 2), "由样本值推断为日期列");
        });
    }

    @Test
    @DisplayName("接口二：xlsx 模板的标量与行块循环填充")
    void fillTemplate() throws IOException {
        byte[] template = loopTemplate("orders");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportResult result = Documents.of(sample(3))
                .template(template)
                .put("title", "2024 年 8 月订单月报")
                .put("author", "运营部")
                .bind("orders", sample(3))
                .output(out)
                .export();

        assertTrue(result.templated());
        assertEquals("poi", result.engine());
        withWorkbook(result.data(), workbook -> {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("2024 年 8 月订单月报", text(sheet.getRow(0), 0), "标量占位符应被替换");
            assertEquals("运营部", text(sheet.getRow(1), 0));

            // 标记行被移除，第 3 行起为展开后的 3 条数据
            assertEquals("SO-1", text(sheet.getRow(2), 0));
            assertEquals("未支付", text(sheet.getRow(2), 1));
            assertEquals("2024/01/15", text(sheet.getRow(2), 2));
            assertEquals("SO-3", text(sheet.getRow(4), 0));
            assertEquals(5, sheet.getLastRowNum() + 1, "标题 + 作者 + 3 条数据");
        });
    }

    @Test
    @DisplayName("接口二：默认主数据标签 list 无需显式 bind")
    void fillTemplateWithDefaultTag() throws IOException {
        byte[] template = loopTemplate("list");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = Documents.of(sample(2))
                .template(template)
                .put("title", "T")
                .put("author", "A")
                .output(out)
                .export()
                .data();
        withWorkbook(data, workbook -> {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("SO-1", text(sheet.getRow(2), 0), "主数据应自动绑定到默认标签 list");
            assertEquals("SO-2", text(sheet.getRow(3), 0));
            assertEquals(4, sheet.getLastRowNum() + 1);
        });
    }

    @Test
    @DisplayName("接口二：循环标签无数据时整块消失，不留空行")
    void fillTemplateWithEmptyTable() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = Documents.of(List.<OrderVo>of())
                .template(loopTemplate("orders"))
                .put("title", "T")
                .put("author", "A")
                .output(out)
                .export()
                .data();
        withWorkbook(data, workbook -> {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals(2, sheet.getLastRowNum() + 1, "仅保留标题与作者行");
        });
    }

    @Test
    @DisplayName("导出到文件并回读")
    void exportToFile(@TempDir Path dir) throws IOException {
        var target = dir.resolve("nested").resolve("订单表.xlsx").toFile();
        ExportResult result = Documents.of(sample(2)).output(target).export();
        assertNotNull(result.file());
        assertTrue(result.file().exists());
        assertTrue(result.file().length() > 0);
        withWorkbook(result.data(), workbook -> assertEquals(4, workbook.getSheetAt(0).getLastRowNum() + 1,
                "标题行 + 表头行 + 2 条数据"));
    }

    @Test
    @DisplayName("引擎自描述可列出 poi 引擎与格式")
    void enginesDescribe() {
        assertTrue(Engines.generatorFormats().contains("xlsx"), Engines.describe());
        assertTrue(Engines.fillerFormats().contains("xlsx"), Engines.describe());
        assertNotNull(Engines.generator("xlsx", null));
        assertEquals("poi", Engines.generator("xlsx", null).engine());
    }

    @Test
    @DisplayName("嵌套占位符：对象属性 xxx.yy、集合下标、循环内 {{@index}} 与未导出字段")
    void nestedPlaceholders() throws IOException {
        List<NestedVo> rows = nestedSample(3);
        ExportResult result = Documents.of(rows)
                .template(nestedTemplate())
                .put("report", Map.of("title", "季报", "owner", Map.of("name", "张三")))
                .put("head", rows)
                .bind("orders", rows)
                .output(new ByteArrayOutputStream())
                .export();

        withWorkbook(result.data(), workbook -> {
            Sheet sheet = workbook.getSheetAt(0);
            // 标量位置的嵌套路径：变量 → Map → 字段，任意深度
            assertEquals("季报 / 张三", text(sheet.getRow(0), 0));
            assertEquals("季报", text(sheet.getRow(0), 1));
            // 集合按下标：head.0.orderNo
            assertEquals("N-1", text(sheet.getRow(1), 0));
            // 循环行内直接在原始对象上读多级嵌套，即使 customer 本身是导出列
            assertEquals("杭州 / 客户甲", text(sheet.getRow(2), 0));
            assertEquals("N-1", text(sheet.getRow(2), 1));
            assertEquals("客户甲", text(sheet.getRow(2), 2), "同名单列被格式化为文本后，嵌套仍走原始对象");
            assertEquals("第1条", text(sheet.getRow(2), 3), "{{@index}} 循环元信息");
            assertEquals("s-1", text(sheet.getRow(2), 4), "未导出为列的字段可用 {{_row.secret}} 取到");
            assertEquals("客户乙", text(sheet.getRow(3), 2));
            assertEquals("第2条", text(sheet.getRow(3), 3));
        });
    }

    /**
     * 嵌套对象验证行。
     */
    @ExportSheet(name = "嵌套", title = "嵌套示例")
    public static class NestedVo {

        /**
         * 订单号。
         */
        @ExportColumn(name = "订单号", order = 1)
        private String orderNo;

        /**
         * 客户：本身是导出列，同时也是其他占位符的嵌套根。
         */
        @ExportColumn(name = "客户", order = 2)
        private Customer customer;

        /**
         * 未导出为列的字段。
         */
        @ExportColumn(ignore = true)
        private String secret;

        /**
         * 构造数据。
         *
         * @param orderNo 订单号
         * @param name    客户名
         * @param city    城市
         * @param secret  内部字段
         * @return 订单
         */
        public static NestedVo of(String orderNo, String name, String city, String secret) {
            NestedVo vo = new NestedVo();
            vo.orderNo = orderNo;
            vo.customer = Customer.of(name, city);
            vo.secret = secret;
            return vo;
        }
    }

    /**
     * 嵌套对象。
     */
    public static class Customer {

        /**
         * 客户名。
         */
        private String name;

        /**
         * 地址。
         */
        private Address address;

        /**
         * 构造客户。
         *
         * @param name 客户名
         * @param city 城市
         * @return 客户
         */
        public static Customer of(String name, String city) {
            Customer customer = new Customer();
            customer.name = name;
            customer.address = Address.of(city);
            return customer;
        }
    }

    /**
     * 嵌套对象。
     */
    public static class Address {

        /**
         * 城市。
         */
        private String city;

        /**
         * 构造地址。
         *
         * @param city 城市
         * @return 地址
         */
        public static Address of(String city) {
            Address address = new Address();
            address.city = city;
            return address;
        }
    }

    /**
     * 构造嵌套样例数据。
     *
     * @param count 条数
     * @return 数据列表
     */
    private static List<NestedVo> nestedSample(int count) {
        List<NestedVo> list = new ArrayList<>();
        String[] names = {"客户甲", "客户乙", "客户丙"};
        for (int i = 0; i < count; i++) {
            list.add(NestedVo.of("N-" + (i + 1), names[i % names.length], "杭州", "s-" + (i + 1)));
        }
        return list;
    }

    /**
     * 构造嵌套占位符模板。
     *
     * <pre>
     * r0: {{report.title}} / {{report.owner.name}}
     * r1: {{head.0.orderNo}}
     * r2: {{#orders}}
     * r3: {{customer.address.city}} / {{customer.name}} | {{customer.name}} | 第{{@index}}条 | {{_row.secret}}
     * r4: {{/orders}}
     * </pre>
     *
     * @return 模板字节
     * @throws IOException 构建异常
     */
    private static byte[] nestedTemplate() throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("nested");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("{{report.title}} / {{report.owner.name}}");
            header.createCell(1).setCellValue("{{report.title}}");
            sheet.createRow(1).createCell(0).setCellValue("{{head.0.orderNo}}");

            sheet.createRow(2).createCell(0).setCellValue("{{#orders}}");
            Row body = sheet.createRow(3);
            body.createCell(0).setCellValue("{{customer.address.city}} / {{customer.name}}");
            body.createCell(1).setCellValue("{{orderNo}}");
            body.createCell(2).setCellValue("{{customer.name}}");
            body.createCell(3).setCellValue("第{{@index}}条");
            body.createCell(4).setCellValue("{{_row.secret}}");
            sheet.createRow(4).createCell(0).setCellValue("{{/orders}}");

            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("旧版二进制 .xls 不再被冒充处理：直接报错并提示改用 xlsx")
    void legacyXlsFailsLoudly(@TempDir Path dir) {
        assertFalse(Engines.generatorFormats().contains("xls"), "POI 只支持 OOXML，不能声明 xls");
        ExportException error = assertThrows(ExportException.class,
                () -> Documents.of(sample(1)).output(dir.resolve("旧表.xls")).export());
        assertTrue(error.getMessage().contains("xlsx"), error.getMessage());
    }

    @Test
    @DisplayName("模板支持直接传入路径，格式与输出路径均由扩展名推断")
    void templateByPath(@TempDir Path dir) throws IOException {
        Path template = dir.resolve("templates").resolve("订单模板.xlsx");
        Files.createDirectories(template.getParent());
        Files.write(template, loopTemplate("orders"));

        Path target = dir.resolve("out").resolve("订单.xlsx");
        ExportResult result = Documents.of(sample(2))
                .template(template)
                .put("title", "2024 年 8 月订单月报")
                .bind("orders", sample(2))
                .output(target)
                .export();

        assertEquals("xlsx", result.format(), "格式应由模板路径扩展名推断");
        assertTrue(result.templated());
        assertEquals(target.toFile(), result.file());
        assertTrue(Files.exists(target));
        withWorkbook(result.data(), workbook ->
                assertEquals(4, workbook.getSheetAt(0).getLastRowNum() + 1, "表头 + 2 行数据"));
    }

    @Test
    @DisplayName("autoCloseStream 默认不关闭调用方流，开启后关闭目标流与模板流")
    void autoCloseStream(@TempDir Path dir) throws IOException {
        TrackingStream untouched = new TrackingStream();
        Documents.of(sample(1)).output(untouched).export();
        assertFalse(untouched.closed, "默认谁打开谁关闭");
        assertTrue(untouched.size() > 0, "关闭前应已写出内容");

        TrackingStream closing = new TrackingStream();
        Documents.of(sample(1)).autoCloseStream(true).output(closing).export();
        assertTrue(closing.closed, "autoCloseStream(true) 应关闭目标流");

        Path template = dir.resolve("t.xlsx");
        Files.write(template, loopTemplate("orders"));
        TrackingInputStream source = new TrackingInputStream(Files.readAllBytes(template));
        Documents.of(sample(1))
                .template(source)
                .bind("orders", sample(1))
                .autoCloseStream(true)
                .output(new TrackingStream())
                .export();
        assertTrue(source.closed, "autoCloseStream(true) 应连模板流一起关闭");

        TrackingInputStream kept = new TrackingInputStream(Files.readAllBytes(template));
        Documents.of(sample(1))
                .template(kept)
                .bind("orders", sample(1))
                .output(new TrackingStream())
                .export();
        assertFalse(kept.closed, "默认不关闭调用方传入的模板流");
    }

    /**
     * 记录关闭状态的输出流。
     */
    private static final class TrackingStream extends ByteArrayOutputStream {

        /**
         * 是否已关闭。
         */
        private boolean closed;

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }
    }

    /**
     * 记录关闭状态的输入流。
     */
    private static final class TrackingInputStream extends ByteArrayInputStream {

        /**
         * 是否已关闭。
         */
        private boolean closed;

        TrackingInputStream(byte[] data) {
            super(data);
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }
    }

    /**
     * 构造模板：标量 + 单行循环块。
     *
     * <pre>
     * r0: {{title}}
     * r1: {{author}}
     * r2: {{#tag}}
     * r3: {{orderNo}} | {{status}} | {{orderDate}}
     * r4: {{/tag}}
     * </pre>
     *
     * @param tag 循环标签
     * @return 模板字节
     * @throws IOException 构建异常
     */
    private static byte[] loopTemplate(String tag) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("月报");
            sheet.createRow(0).createCell(0).setCellValue("{{title}}");
            sheet.createRow(1).createCell(0).setCellValue("{{author}}");
            sheet.createRow(2).createCell(0).setCellValue("{{#" + tag + "}}");

            Row body = sheet.createRow(3);
            body.createCell(0).setCellValue("{{orderNo}}");
            body.createCell(1).setCellValue("{{status}}");
            body.createCell(2).setCellValue("{{orderDate}}");

            sheet.createRow(4).createCell(0).setCellValue("{{/" + tag + "}}");
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
