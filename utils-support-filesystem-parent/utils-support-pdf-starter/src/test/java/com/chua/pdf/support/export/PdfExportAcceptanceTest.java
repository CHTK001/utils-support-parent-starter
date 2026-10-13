package com.chua.pdf.support.export;

import com.chua.common.support.export.Documents;
import com.chua.common.support.export.Engines;
import com.chua.common.support.export.ExportFormats;
import com.chua.common.support.export.ExportResult;
import com.chua.common.support.export.annotation.ExportColumn;
import com.chua.common.support.export.annotation.ExportSheet;
import com.chua.common.support.export.meta.ColumnType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.PDFTextStripper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 端到端验收测试 —— 接口一生成 pdf、接口二填充 AcroForm pdf 模板。
 *
 * @author CH
 * @since 4.0.0.42
 */
class PdfExportAcceptanceTest {

    /**
     * 验收数据行。
     */
    @ExportSheet(name = "订单", title = "订单月报")
    public static class OrderVo {

        /**
         * 订单号。
         */
        @ExportColumn(name = "ORDER_NO", order = 1)
        private String orderNo;

        /**
         * 金额。
         */
        @ExportColumn(name = "AMOUNT", order = 2, type = ColumnType.AMOUNT)
        private BigDecimal amount;

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
         * @return 订单
         */
        public static OrderVo of(String orderNo, String amount) {
            OrderVo vo = new OrderVo();
            vo.orderNo = orderNo;
            vo.amount = new BigDecimal(amount);
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
            list.add(OrderVo.of("SO-" + (i + 1), (i + 1) + ".5"));
        }
        return list;
    }

    /**
     * 在已加载的 PDF 上执行断言。
     *
     * @param data   pdf 字节
     * @param action 断言逻辑
     * @throws IOException 读取异常
     */
    private static void withDocument(byte[] data, ThrowingConsumer<PDDocument> action) throws IOException {
        try (RandomAccessReadBuffer buffer = new RandomAccessReadBuffer(new ByteArrayInputStream(data));
             PDDocument document = Loader.loadPDF(buffer)) {
            action.accept(document);
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

    @Test
    @DisplayName("接口一：注解解析 → 生成 pdf，标题、表头与数据均已写入内容流")
    void generateFromList() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportResult result = Documents.of(sample(3))
                .format(ExportFormats.PDF)
                .output(out)
                .export();

        assertEquals("pdf", result.format());
        assertEquals("pdfbox", result.engine());
        assertEquals(3, result.rows());
        assertFalse(result.templated());
        assertTrue(result.data().length > 0);

        withDocument(result.data(), document -> {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("订单月报"), "应包含标题");
            assertTrue(text.contains("ORDER_NO"), "应包含表头");
            assertTrue(text.contains("SO-1"), "应包含首行数据");
            assertFalse(text.contains("traceId"), "ignore=true 的列不应出现");
        });
    }

    @Test
    @DisplayName("接口一：rowsPerSheet 超限时自动分页")
    void paginate() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = Documents.of(sample(30))
                .format(ExportFormats.PDF)
                .rowsPerSheet(5)
                .output(out)
                .export()
                .data();
        withDocument(data, document -> assertTrue(document.getNumberOfPages() > 1,
                "30 行 / 每页 5 行应产生多页，实际 " + document.getNumberOfPages()));
    }

    @Test
    @DisplayName("接口一：导出到文件并回读")
    void exportToFile(@TempDir Path dir) throws IOException {
        var target = dir.resolve("nested").resolve("订单.pdf").toFile();
        ExportResult result = Documents.of(sample(2)).format(ExportFormats.PDF).output(target).export();
        assertNotNull(result.file());
        assertTrue(result.file().exists());
        assertTrue(result.file().length() > 0);
        withDocument(result.data(), document -> assertEquals(1, document.getNumberOfPages()));
    }

    @Test
    @DisplayName("接口二：填充 AcroForm 表单域（文本域 / 复选框 / 下拉框）")
    void fillFormTemplate() throws IOException {
        // 表单外观流由 /DR 字体渲染：Helvetica 只有 WinAnsi，渲染不了中文
        boolean cjk = PdfFonts.cjkAvailable();
        String title = cjk ? "月度对账单" : "Monthly Statement";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportResult result = Documents.of(sample(1))
                .template(formTemplate())
                .put("title", title)
                .put("orderNo", "SO-1001")
                .put("paid", true)
                .put("level", "VIP")
                .output(out)
                .export();

        assertTrue(result.templated());
        assertEquals("pdfbox", result.engine());

        withDocument(result.data(), document -> {
            var form = document.getDocumentCatalog().getAcroForm();
            assertNotNull(form);
            assertEquals(title, text(form, "title"));
            assertEquals("SO-1001", text(form, "orderNo"));
            assertEquals(title, text(form, "remark"), "模板默认值中的 {{title}} 应被替换");
            assertTrue(check(form, "paid"), "true 应勾选");
            assertEquals("VIP", choice(form, "level"), "下拉框应写入取值");
        });
    }

    @Test
    @DisplayName("接口二：未提供对应变量时保留模板中的原始取值")
    void fillFormKeepsDefaults() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = Documents.of(sample(1))
                .template(formTemplate())
                .put("title", "T")
                .put("orderNo", "SO-9")
                .put("remark", "R")
                .put("paid", false)
                .put("level", "VIP")
                .output(out)
                .export()
                .data();
        withDocument(data, document -> {
            var form = document.getDocumentCatalog().getAcroForm();
            assertNotNull(form);
            assertEquals("SO-9", text(form, "orderNo"));
            assertFalse(check(form, "paid"), "false 不应勾选");
        });
    }

    @Test
    @DisplayName("接口二：模板无表单域时抛出可读异常")
    void fillWithoutForm() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var error = assertThrows(RuntimeException.class, () -> Documents.of(sample(1))
                .template(plainPdf())
                .output(out)
                .export());
        assertNotNull(error.getMessage());
    }

    @Test
    @DisplayName("引擎自描述可列出 pdf 生成与填充引擎")
    void enginesDescribe() {
        assertTrue(Engines.generatorFormats().contains("pdf"), Engines.describe());
        assertTrue(Engines.fillerFormats().contains("pdf"), Engines.describe());
        assertEquals("pdfbox", Engines.generator("pdf", null).engine());
        assertEquals("pdfbox", Engines.filler("pdf", null).engine());
    }

    /**
     * 读取文本域取值。
     *
     * @param form 表单
     * @param name 域名
     * @return 取值
     * @throws IOException 读取异常
     */
    private static String text(org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm form, String name)
            throws IOException {
        PDField field = form.getField(name);
        assertNotNull(field, "缺少表单域: " + name);
        return field.getValueAsString();
    }

    /**
     * 读取下拉框选中值。
     *
     * <p>PDFBox 3 的 {@code PDChoice#getValue()} 返回列表。</p>
     *
     * @param form 表单
     * @param name 域名
     * @return 选中值
     * @throws IOException 读取异常
     */
    private static String choice(org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm form, String name)
            throws IOException {
        PDField field = form.getField(name);
        assertNotNull(field, "缺少表单域: " + name);
        List<String> values = field instanceof PDComboBox combo ? combo.getValue() : List.of();
        assertEquals(1, values.size(), "下拉框应恰好选中一项");
        return values.get(0);
    }

    /**
     * 读取复选框状态。
     *
     * @param form 表单
     * @param name 域名
     * @return 是否勾选
     */
    private static boolean check(org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm form, String name)
            throws IOException {
        PDField field = form.getField(name);
        assertNotNull(field, "缺少表单域: " + name);
        return field instanceof PDCheckBox box && box.isChecked();
    }

    @Test
    @DisplayName("接口二：表单域默认值里的嵌套占位符按变量路径取值")
    void fillNestedTemplate() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportResult result = Documents.of(sample(1))
                .template(nestedFormTemplate())
                .put("report", Map.of("title", "Monthly", "owner", Map.of("name", "Zhang")))
                .put("head", sample(2))
                .output(out)
                .export();

        withDocument(result.data(), document -> {
            var form = document.getDocumentCatalog().getAcroForm();
            assertNotNull(form);
            assertEquals("Monthly", text(form, "rTitle"), "变量 → Map");
            assertEquals("Zhang", text(form, "rOwner"), "变量 → Map → Map，两级嵌套");
            assertEquals("SO-2", text(form, "rHead"), "集合按下标");
        });
    }

    /**
     * 构造嵌套占位符的 AcroForm 模板。
     *
     * @return 模板字节
     * @throws IOException 构建异常
     */
    private static byte[] nestedFormTemplate() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            var form = new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(document);
            document.getDocumentCatalog().setAcroForm(form);
            var resources = new org.apache.pdfbox.pdmodel.PDResources();
            resources.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"), formFont(document));
            form.setDefaultResources(resources);

            int y = 700;
            y = addTextField(document, page, form, "rTitle", "{{report.title}}", y);
            y = addTextField(document, page, form, "rOwner", "{{report.owner.name}}", y);
            addTextField(document, page, form, "rHead", "{{head.1.orderNo}}", y);

            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("模板支持直接传入路径，格式由模板与输出路径扩展名推断")
    void templateByPath(@TempDir Path dir) throws IOException {
        Path template = dir.resolve("templates").resolve("对账单模板.pdf");
        Files.createDirectories(template.getParent());
        Files.write(template, formTemplate());

        Path target = dir.resolve("out").resolve("对账单.pdf");
        ExportResult result = Documents.of(sample(1))
                .template(template)
                .put("title", "T")
                .put("orderNo", "SO-77")
                .put("remark", "R")
                .put("paid", false)
                .put("level", "VIP")
                .output(target)
                .export();

        assertEquals("pdf", result.format(), "格式应由模板路径扩展名推断");
        assertTrue(result.templated());
        assertTrue(Files.exists(target));
        withDocument(result.data(), document ->
                assertEquals("SO-77", text(document.getDocumentCatalog().getAcroForm(), "orderNo")));
    }

    @Test
    @DisplayName("autoCloseStream 默认不关闭调用方流，开启后关闭目标流与模板流")
    void autoCloseStream(@TempDir Path dir) throws IOException {
        TrackingStream untouched = new TrackingStream();
        Documents.of(sample(1)).format(ExportFormats.PDF).output(untouched).export();
        assertFalse(untouched.closed, "默认谁打开谁关闭");
        assertTrue(untouched.size() > 0);

        TrackingStream closing = new TrackingStream();
        Documents.of(sample(1)).format(ExportFormats.PDF).autoCloseStream(true).output(closing).export();
        assertTrue(closing.closed, "autoCloseStream(true) 应关闭目标流");

        Path file = dir.resolve("t.pdf");
        Files.write(file, formTemplate());
        TrackingInputStream source = new TrackingInputStream(Files.readAllBytes(file));
        Documents.of(sample(1))
                .template(source)
                .put("orderNo", "SO-1")
                .autoCloseStream(true)
                .output(new TrackingStream())
                .export();
        assertTrue(source.closed, "autoCloseStream(true) 应连模板流一起关闭");

        TrackingInputStream kept = new TrackingInputStream(Files.readAllBytes(file));
        Documents.of(sample(1))
                .template(kept)
                .put("orderNo", "SO-2")
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
    private static final class TrackingInputStream extends java.io.ByteArrayInputStream {

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
     * 构造含 AcroForm 的 PDF 模板：文本域 + 复选框 + 下拉框。
     *
     * @return 模板字节
     * @throws IOException 构建异常
     */
    private static byte[] formTemplate() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            var form = new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(document);
            document.getDocumentCatalog().setAcroForm(form);

            // 表单域生成外观流需要 /DR 字体资源；中文需嵌入中文字体，否则 PDFBox 会报 notdef
            var resources = new org.apache.pdfbox.pdmodel.PDResources();
            resources.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"), formFont(document));
            form.setDefaultResources(resources);

            int y = 700;
            y = addTextField(document, page, form, "title", "{{title}}", y);
            y = addTextField(document, page, form, "orderNo", "{{orderNo}}", y);
            y = addTextField(document, page, form, "remark", "{{title}}", y);

            PDCheckBox checkBox = new PDCheckBox(form);
            checkBox.setPartialName("paid");
            drawField(document, page, form, checkBox, 40, y);
            y -= 40;

            PDComboBox combo = new PDComboBox(form);
            combo.setPartialName("level");
            combo.setOptions(List.of("VIP", "NORMAL"));
            drawField(document, page, form, combo, 40, y);

            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 构造无表单域的普通 PDF。
     *
     * @return 模板字节
     * @throws IOException 构建异常
     */
    private static byte[] plainPdf() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                stream.showText("no acro form here");
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 选择表单默认外观字体：有中文字体时嵌入中文字体，否则退回 Helvetica。
     *
     * @param document 文档
     * @return 字体
     * @throws IOException 加载失败
     */
    private static org.apache.pdfbox.pdmodel.font.PDFont formFont(PDDocument document) throws IOException {
        if (PdfFonts.cjkAvailable()) {
            return PdfFonts.load(document);
        }
        return new org.apache.pdfbox.pdmodel.font.PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    /**
     * 添加文本域。
     *
     * @param document 文档
     * @param page     页面
     * @param form     表单
     * @param name     域名
     * @param value    默认值
     * @param y        纵坐标
     * @return 下一行的纵坐标
     * @throws IOException 构建异常
     */
    private static int addTextField(PDDocument document, PDPage page,
                                    org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm form,
                                    String name, String value, int y) throws IOException {
        PDTextField field = new PDTextField(form);
        field.setPartialName(name);
        field.setDefaultAppearance("/Helv 10 Tf 0 g");
        field.setValue(value);
        drawField(document, page, form, field, 40, y);
        return y - 40;
    }

    /**
     * 把表单域摆放到页面上。
     *
     * @param document 文档
     * @param page     页面
     * @param form     表单
     * @param field    域
     * @param x        横坐标
     * @param y        纵坐标
     * @throws IOException 构建异常
     */
    private static void drawField(PDDocument document, PDPage page,
                                  org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm form,
                                  PDField field, int x, int y) throws IOException {
        var rect = new org.apache.pdfbox.pdmodel.common.PDRectangle(x, y - 4, 300, 20);
        var annotation = field.getWidgets().get(0);
        annotation.setRectangle(rect);
        annotation.setPage(page);
        page.getAnnotations().add(annotation);
        form.getFields().add(field);
    }

    /**
     * 保证输入流工具被引用。
     */
    @Test
    @DisplayName("输入流工具自检")
    void guard() throws IOException {
        try (InputStream in = new ByteArrayInputStream(plainPdf())) {
            assertTrue(in.read() >= 0);
        }
    }
}
