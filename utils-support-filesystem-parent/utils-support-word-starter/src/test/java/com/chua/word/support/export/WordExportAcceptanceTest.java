package com.chua.word.support.export;

import com.chua.common.support.export.Documents;
import com.chua.common.support.export.Engines;
import com.chua.common.support.export.ExportFormats;
import com.chua.common.support.export.ExportResult;
import com.chua.common.support.export.annotation.ExportColumn;
import com.chua.common.support.export.annotation.ExportSheet;
import com.chua.common.support.export.meta.ColumnType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Word 端到端验收测试 —— 接口一生成 docx、接口二填充 docx 模板（原生 POI）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class WordExportAcceptanceTest {

    /**
     * 验收数据行。
     */
    @ExportSheet(name = "订单", title = "订单月报")
    public static class OrderVo {

        /**
         * 订单号。
         */
        @ExportColumn(name = "订单号", order = 1)
        private String orderNo;

        /**
         * 金额。
         */
        @ExportColumn(name = "金额", order = 2, type = ColumnType.AMOUNT)
        private BigDecimal amount;

        /**
         * 状态。
         */
        @ExportColumn(name = "状态", order = 3, type = ColumnType.ENUM, enumNames = {"0=未支付", "1=已支付"})
        private Integer status;

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
         * @return 订单
         */
        public static OrderVo of(String orderNo, String amount, int status) {
            OrderVo vo = new OrderVo();
            vo.orderNo = orderNo;
            vo.amount = new BigDecimal(amount);
            vo.status = status;
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
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> OrderVo.of("SO-" + (i + 1), i + ".5", i % 2))
                .toList();
    }

    /**
     * 读取单元格文本。
     *
     * @param cell 单元格
     * @return 文本
     */
    private static String text(XWPFTableCell cell) {
        StringBuilder sb = new StringBuilder();
        for (var paragraph : cell.getParagraphs()) {
            for (var run : paragraph.getRuns()) {
                sb.append(run.text());
            }
        }
        return sb.toString();
    }

    /**
     * 读取整表文本。
     *
     * @param table 表格
     * @return 逐行文本
     */
    private static List<String> rows(XWPFTable table) {
        List<String> result = new java.util.ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new java.util.ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(text(cell));
            }
            result.add(cells.toString());
        }
        return result;
    }

    /**
     * 读取段落文本。
     *
     * @param document 文档
     * @return 段落文本列表
     */
    private static List<String> paragraphs(XWPFDocument document) {
        List<String> result = new java.util.ArrayList<>();
        for (var paragraph : document.getParagraphs()) {
            result.add(paragraph.getText());
        }
        return result;
    }

    @Test
    @DisplayName("接口一：注解解析 → 生成 docx，标题、表格与枚举映射正确")
    void generateFromList() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportResult result = Documents.of(sample(2))
                .format(ExportFormats.DOCX)
                .output(out)
                .export();

        assertEquals("docx", result.format());
        assertEquals("poi-word", result.engine());
        assertEquals(2, result.rows());
        assertFalse(result.templated());

        try (InputStream in = new ByteArrayInputStream(result.data());
             XWPFDocument document = new XWPFDocument(in)) {
            assertEquals(List.of("订单月报"), paragraphs(document));
            assertEquals(1, document.getTables().size());
            List<String> table = rows(document.getTables().get(0));
            assertEquals(3, table.size(), "表头 + 2 条数据");
            assertEquals("[订单号, 金额, 状态]", table.get(0));
            assertEquals("[SO-1, 0.50, 未支付]", table.get(1));
            assertEquals("[SO-2, 1.50, 已支付]", table.get(2));
        }
    }

    @Test
    @DisplayName("接口一：rowsPerSheet 超限时自动分页并重复表头")
    void paginate() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = Documents.of(sample(5))
                .format(ExportFormats.DOCX)
                .rowsPerSheet(2)
                .output(out)
                .export()
                .data();
        try (InputStream in = new ByteArrayInputStream(data);
             XWPFDocument document = new XWPFDocument(in)) {
            assertEquals(3, document.getTables().size(), "5 行 / 每页 2 行 → 3 页");
            for (int i = 0; i < 3; i++) {
                assertEquals("[订单号, 金额, 状态]", rows(document.getTables().get(i)).get(0),
                        "每页均应重复表头");
            }
        }
    }

    @Test
    @DisplayName("接口二：填充 docx 模板的标量与表格行循环")
    void fillTemplate() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportResult result = Documents.of(sample(3))
                .template(template("orders"))
                .put("title", "2024 年 8 月订单月报")
                .bind("orders", sample(3))
                .output(out)
                .export();

        assertTrue(result.templated());
        assertEquals("poi-word", result.engine());

        try (InputStream in = new ByteArrayInputStream(result.data());
             XWPFDocument document = new XWPFDocument(in)) {
            assertEquals(List.of("2024 年 8 月订单月报"), paragraphs(document));
            assertEquals(1, document.getTables().size());
            List<String> table = rows(document.getTables().get(0));
            assertEquals(4, table.size(), "表头 + 3 条数据");
            assertEquals("[订单号, 状态]", table.get(0));
            assertEquals("[SO-1, 未支付]", table.get(1));
            assertEquals("[SO-3, 未支付]", table.get(3));
        }
    }

    @Test
    @DisplayName("接口二：默认主数据标签 list 无需显式 bind")
    void fillTemplateWithDefaultTag() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = Documents.of(sample(2))
                .template(template("list"))
                .put("title", "T")
                .output(out)
                .export()
                .data();
        try (InputStream in = new ByteArrayInputStream(data);
             XWPFDocument document = new XWPFDocument(in)) {
            assertEquals(3, rows(document.getTables().get(0)).size(), "表头 + 2 条数据");
        }
    }

    @Test
    @DisplayName("接口二：循环标签无数据时整块消失")
    void fillTemplateWithEmptyTable() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = Documents.of(List.<OrderVo>of())
                .template(template("orders"))
                .put("title", "T")
                .output(out)
                .export()
                .data();
        try (InputStream in = new ByteArrayInputStream(data);
             XWPFDocument document = new XWPFDocument(in)) {
            assertEquals(1, rows(document.getTables().get(0)).size(), "仅保留表头行");
        }
    }

    @Test
    @DisplayName("引擎自描述可列出 docx 生成与填充引擎")
    void enginesDescribe() {
        assertTrue(Engines.generatorFormats().contains("docx"), Engines.describe());
        assertTrue(Engines.fillerFormats().contains("docx"), Engines.describe());
        assertEquals("poi-word", Engines.generator("docx", null).engine());
        assertEquals("poi-word", Engines.filler("docx", null).engine());
    }

    @Test
    @DisplayName("嵌套占位符：对象属性、集合下标、循环元信息与同格多占位符")
    void nestedPlaceholders() throws IOException {
        ExportResult result = Documents.of(nestedSample(2))
                .template(nestedTemplate())
                .put("report", Map.of("title", "季报"))
                .put("head", nestedSample(2))
                .bind("rows", nestedSample(2))
                .output(new ByteArrayOutputStream())
                .export();

        try (InputStream in = new ByteArrayInputStream(result.data());
             XWPFDocument document = new XWPFDocument(in)) {
            assertEquals(List.of("季报", "N-1"), paragraphs(document), "标量位置的嵌套路径");
            List<String> table = rows(document.getTables().get(0));
            assertEquals(3, table.size(), "表头 + 2 条数据");
            // 一格内两个占位符 + 三级嵌套
            assertEquals("[杭州 / 客户甲, 客户甲, 第1条, s-N-1]", table.get(1));
            assertEquals("[杭州 / 客户乙, 客户乙, 第2条, s-N-2]", table.get(2));
        }
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
         * 客户：本身是导出列，同时也是嵌套根。
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
         * @return 数据
         */
        public static NestedVo of(String orderNo, String name) {
            NestedVo vo = new NestedVo();
            vo.orderNo = orderNo;
            vo.customer = Customer.of(name);
            vo.secret = "s-" + orderNo;
            return vo;
        }
    }

    /**
     * 客户。
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
         * @return 客户
         */
        public static Customer of(String name) {
            Customer customer = new Customer();
            customer.name = name;
            customer.address = new Address();
            customer.address.city = "杭州";
            return customer;
        }
    }

    /**
     * 地址。
     */
    public static class Address {

        /**
         * 城市。
         */
        private String city;
    }

    /**
     * 构造嵌套样例数据。
     *
     * @param count 条数
     * @return 数据列表
     */
    private static List<NestedVo> nestedSample(int count) {
        List<NestedVo> list = new java.util.ArrayList<>();
        String[] names = {"客户甲", "客户乙", "客户丙"};
        for (int i = 0; i < count; i++) {
            list.add(NestedVo.of("N-" + (i + 1), names[i % names.length]));
        }
        return list;
    }

    /**
     * 构造嵌套占位符模板。
     *
     * <pre>
     * p0: {{report.title}}
     * p1: {{head.0.orderNo}}
     * t0: 表头
     * t1: {{#rows}}
     * t2: {{customer.address.city}} / {{customer.name}} | {{customer.name}} | 第{{@index}}条 | {{_row.secret}}
     * t3: {{/rows}}
     * </pre>
     *
     * @return 模板字节
     * @throws IOException 构建异常
     */
    private static byte[] nestedTemplate() throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("{{report.title}}");
            document.createParagraph().createRun().setText("{{head.0.orderNo}}");

            XWPFTable table = document.createTable(4, 4);
            fill(table.getRow(0), "列", "", "", "");
            fill(table.getRow(1), "{{#rows}}", "", "", "");
            fill(table.getRow(2), "{{customer.address.city}} / {{customer.name}}",
                    "{{customer.name}}", "第{{@index}}条", "{{_row.secret}}");
            fill(table.getRow(3), "{{/rows}}", "", "", "");

            document.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("模板支持直接传入路径，格式与输出路径均由扩展名推断")
    void templateByPath(@TempDir Path dir) throws IOException {
        Path template = dir.resolve("templates").resolve("订单模板.docx");
        Files.createDirectories(template.getParent());
        Files.write(template, template("orders"));

        Path target = dir.resolve("out").resolve("订单.docx");
        ExportResult result = Documents.of(sample(2))
                .template(template)
                .put("title", "2024 年 8 月订单月报")
                .bind("orders", sample(2))
                .output(target)
                .export();

        assertEquals("docx", result.format(), "格式应由模板路径扩展名推断");
        assertTrue(result.templated());
        assertTrue(Files.exists(target));
        try (InputStream in = new ByteArrayInputStream(result.data());
             XWPFDocument document = new XWPFDocument(in)) {
            assertEquals(List.of("2024 年 8 月订单月报"), paragraphs(document));
            assertEquals(3, rows(document.getTables().get(0)).size(), "表头 + 2 条数据");
        }
    }

    @Test
    @DisplayName("autoCloseStream 默认不关闭调用方流，开启后关闭目标流与模板流")
    void autoCloseStream(@TempDir Path dir) throws IOException {
        TrackingStream untouched = new TrackingStream();
        Documents.of(sample(1)).format(ExportFormats.DOCX).output(untouched).export();
        assertFalse(untouched.closed, "默认谁打开谁关闭");
        assertTrue(untouched.size() > 0);

        TrackingStream closing = new TrackingStream();
        Documents.of(sample(1)).format(ExportFormats.DOCX).autoCloseStream(true).output(closing).export();
        assertTrue(closing.closed, "autoCloseStream(true) 应关闭目标流");

        java.nio.file.Path file = dir.resolve("t.docx");
        Files.write(file, template("orders"));
        TrackingInputStream source = new TrackingInputStream(Files.readAllBytes(file));
        Documents.of(sample(1))
                .template(source)
                .bind("orders", sample(1))
                .autoCloseStream(true)
                .output(new TrackingStream())
                .export();
        assertTrue(source.closed, "autoCloseStream(true) 应连模板流一起关闭");

        TrackingInputStream kept = new TrackingInputStream(Files.readAllBytes(file));
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
     * 构造模板：标题段落 + 表头行 + 标记行 + 单行循环模板 + 标记行。
     *
     * @param tag 循环标签
     * @return 模板字节
     * @throws IOException 构建异常
     */
    private static byte[] template(String tag) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("{{title}}");

            XWPFTable table = document.createTable(4, 2);
            fill(table.getRow(0), "订单号", "状态");
            fill(table.getRow(1), "{{#" + tag + "}}", "");
            fill(table.getRow(2), "{{orderNo}}", "{{status}}");
            fill(table.getRow(3), "{{/" + tag + "}}", "");

            document.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 写入一行文本。
     *
     * @param row   行
     * @param texts 文本
     */
    private static void fill(XWPFTableRow row, String... texts) {
        for (int i = 0; i < texts.length; i++) {
            XWPFTableCell cell = row.getCell(i);
            if (cell == null) {
                continue;
            }
            cell.removeParagraph(0);
            cell.addParagraph().createRun().setText(texts[i]);
        }
    }
}
