package com.chua.common.support.export;

import com.chua.common.support.export.annotation.ExportColumn;
import com.chua.common.support.export.annotation.ExportSheet;
import com.chua.common.support.export.builder.DataExportBuilder;
import com.chua.common.support.export.builder.TemplateExportBuilder;
import com.chua.common.support.export.meta.ColumnType;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.support.Rows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一门面与构建器验收测试 —— 链式 API、{@code template()} 模式切换、取值与格式化。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DocumentsFacadeTest {

    /**
     * 验收数据对象。
     */
    @ExportSheet(name = "订单")
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
         * 备注。
         */
        @ExportColumn(name = "备注", order = 4)
        private String remark;

        /**
         * 订单号。
         *
         * @return 订单号
         */
        public String getOrderNo() {
            return orderNo;
        }

        /**
         * 设置订单号。
         *
         * @param orderNo 订单号
         */
        public void setOrderNo(String orderNo) {
            this.orderNo = orderNo;
        }

        /**
         * 金额。
         *
         * @return 金额
         */
        public BigDecimal getAmount() {
            return amount;
        }

        /**
         * 设置金额。
         *
         * @param amount 金额
         */
        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        /**
         * 状态。
         *
         * @return 状态
         */
        public Integer getStatus() {
            return status;
        }

        /**
         * 设置状态。
         *
         * @param status 状态
         */
        public void setStatus(Integer status) {
            this.status = status;
        }

        /**
         * 备注。
         *
         * @return 备注
         */
        public String getRemark() {
            return remark;
        }

        /**
         * 设置备注。
         *
         * @param remark 备注
         */
        public void setRemark(String remark) {
            this.remark = remark;
        }
    }

    /**
     * 构造样例数据。
     *
     * @return 订单列表
     */
    private static List<OrderVo> sample() {
        return List.of(
                order("SO-001", "12.5", 1, "第一批"),
                order("SO-002", "8", 0, "第二批"));
    }

    /**
     * 构造单条订单。
     *
     * @param orderNo 订单号
     * @param amount  金额
     * @param status  状态
     * @param remark  备注
     * @return 订单
     */
    private static OrderVo order(String orderNo, String amount, int status, String remark) {
        OrderVo vo = new OrderVo();
        vo.setOrderNo(orderNo);
        vo.setAmount(new BigDecimal(amount));
        vo.setStatus(status);
        vo.setRemark(remark);
        return vo;
    }

    @Test
    @DisplayName("接口一：List → 文档，非模板模式解析注解并预览元数据")
    void dataModePreview() {
        DataExportBuilder builder = Documents.of(sample()).sheet("订单表");
        DocumentMeta meta = builder.preview();
        assertEquals("订单表", meta.resolveSheetName());
        assertEquals(List.of("订单号", "金额", "状态", "备注"), meta.headers());
    }

    @Test
    @DisplayName("链式配置：列限定同时决定顺序并可覆盖注解的 ignore")
    void columnRestriction() {
        DocumentMeta meta = Documents.of(sample())
                .columns("remark", "orderNo")
                .preview();
        assertEquals(List.of("备注", "订单号"), meta.headers(),
                "columns() 应同时决定列顺序并覆盖注解的 ignore");
    }

    @Test
    @DisplayName("链式配置：exclude 排除列")
    void excludeColumn() {
        DocumentMeta meta = Documents.of(sample()).exclude("amount").preview();
        assertEquals(List.of("订单号", "状态", "备注"), meta.headers());
    }

    @Test
    @DisplayName("template() 切换到模板构建器，并自动绑定主数据")
    void templateSwitch() {
        DataExportBuilder start = Documents.of(sample()).sheet("订单表");
        TemplateExportBuilder templated = start.template("classpath:noop.bin")
                .put("title", "订单月报")
                .bind("orders", sample());
        assertInstanceOf(TemplateExportBuilder.class, templated);
        assertEquals(start, templated.source());

        TemplateContext context = templated.context();
        assertEquals("订单表", context.meta().resolveSheetName());
        assertEquals("订单月报", context.variables().get("title"));
        assertEquals(2, context.variables().get("count"));
        assertEquals(2, context.table("orders").size());
        assertEquals(2, context.table("list").size(), "主数据应自动绑定到默认标签 list");
    }

    @Test
    @DisplayName("模板模式：每行展开为以 属性名/标签/标题 为 key 的 Map")
    void flattenRow() {
        TemplateContext context = Documents.of(sample())
                .template("classpath:noop.bin")
                .context();
        Map<String, Object> first = context.table("list").get(0);
        assertEquals("SO-001", first.get("orderNo"));
        assertEquals("SO-001", first.get("订单号"));
        assertEquals(1, first.get("_index"));
        assertEquals(new BigDecimal("12.50"), first.get("amount"), "AMOUNT 应保留两位小数");
        assertEquals("已支付", first.get("status"), "ENUM 应按 enumNames 映射");
    }

    @Test
    @DisplayName("值转换：AMOUNT 补零、ENUM 映射、空值默认值、日期格式化")
    void valueFormatting() {
        DocumentMeta meta = Documents.of(sample()).preview();
        assertEquals(new BigDecimal("12.50"), Rows.display(sample().get(0), meta.find("amount").orElseThrow()));
        assertEquals("已支付", Rows.display(sample().get(0), meta.find("status").orElseThrow()));
        assertEquals("未支付", Rows.display(sample().get(1), meta.find("status").orElseThrow()));

        DocumentMeta defaults = Documents.of(List.<OrderVo>of(new OrderVo()))
                .meta(it -> it.column("remark").defaultValue("-"))
                .preview();
        assertEquals("-", Rows.display(new OrderVo(), defaults.find("remark").orElseThrow()));

        DocumentMeta dateMeta = com.chua.common.support.export.resolver.MetaResolvers
                .resolve(DateBean.class, List.of(new com.chua.common.support.export.resolver.JavabeanMetaResolver()));
        DateBean bean = new DateBean(LocalDateTime.of(2024, 5, 6, 7, 8, 9));
        assertEquals("2024-05-06 07:08:09", Rows.display(bean, dateMeta.find("time").orElseThrow()));

        dateMeta.column("time").dateFormat("yyyy/MM/dd");
        assertEquals("2024/05/06", Rows.display(bean, dateMeta.find("time").orElseThrow()));
    }

    @Test
    @DisplayName("List / 数组 / 单对象均可归一化为行数据")
    void rowNormalization() {
        assertEquals(2, Rows.normalize(List.of(1, 2)).size());
        assertEquals(3, Rows.normalize(new int[]{1, 2, 3}).size());
        assertEquals(1, Rows.normalize("only").size());
        assertEquals(0, Rows.normalize(null).size());
    }

    @Test
    @DisplayName("未注册引擎时抛出可读异常")
    void missingEngine() {
        ExportException error = assertThrows(ExportException.class,
                () -> Documents.of(sample()).format("unknown").export());
        assertTrue(error.getMessage().contains("unknown"), error.getMessage());
    }

    @Test
    @DisplayName("格式推断：默认 xlsx，可由输出文件名覆盖")
    void formatInference() {
        assertEquals("xlsx", ExportFormats.of("a/b/report.xlsx"));
        assertEquals("docx", ExportFormats.of("report.docx"));
        assertEquals("pdf", ExportFormats.of("report.PDF"));
        assertEquals("xlsx", ExportFormats.normalize(".XLSX"));
        org.junit.jupiter.api.Assertions.assertNull(ExportFormats.of("no-extension"));
    }

    @Test
    @DisplayName("模板加载：缺失模板时抛出可读异常")
    void missingTemplate() {
        assertThrows(ExportException.class,
                () -> Documents.of(sample()).template("classpath:not-exists.xlsx").export());
    }

    @Test
    @DisplayName("autoCloseStream：默认关闭开关为关，开启后随模式切换继承")
    void autoCloseStreamFlag() {
        assertFalse(Documents.of(sample()).configBuilder().build().isAutoCloseStream(), "默认不接管流");
        assertTrue(Documents.of(sample()).autoCloseStream(true).configBuilder().build().isAutoCloseStream());
        assertTrue(Documents.of(sample())
                .autoCloseStream(true)
                .template("classpath:noop.docx")
                .context()
                .config()
                .isAutoCloseStream(), "切换到模板模式后应继承该开关");
        assertFalse(Documents.ofTemplate(Path.of("noop.docx"))
                .context()
                .config()
                .isAutoCloseStream());
    }

    @Test
    @DisplayName("模板与输出支持直接传入 Path")
    void pathOverloads() {
        TemplateExportBuilder templated = Documents.of(sample()).template(Path.of("templates/report.docx"));
        assertEquals(TemplateExportBuilder.class, templated.getClass());
        assertEquals(TemplateExportBuilder.class, Documents.ofTemplate(Path.of("a.xlsx")).getClass());

        Path target = Path.of("out").resolve("report.docx");
        assertEquals(target.toFile(), Documents.of(sample()).output(target).file());
        TemplateExportBuilder direct = Documents.ofTemplate(Path.of("a.docx"));
        assertEquals(direct, direct.output(target), "output(Path) 应返回自身");
        assertEquals(direct, direct.template(Path.of("b.docx")), "template(Path) 应返回自身");
    }

    @Test
    @DisplayName("旧版二进制格式给出替代建议：doc -> docx")
    void legacyFormatHint() {
        assertEquals("docx", ExportFormats.replacementOf("doc"));
        assertEquals("docx", ExportFormats.replacementOf(".DOT"));
        assertEquals("xlsx", ExportFormats.replacementOf("xls"));
        assertNull(ExportFormats.replacementOf("docx"));
        assertTrue(assertThrows(ExportException.class, () -> Engines.generator("doc", null))
                .getMessage().contains("docx"), "缺少引擎时应提示改用 docx");
    }

    /**
     * 日期格式化验证对象。
     */
    public static class DateBean {

        /**
         * 时间。
         */
        private LocalDateTime time;

        /**
         * 构造对象。
         *
         * @param time 时间
         */
        public DateBean(LocalDateTime time) {
            this.time = time;
        }

        /**
         * 时间。
         *
         * @return 时间
         */
        public LocalDateTime getTime() {
            return time;
        }

        /**
         * 设置时间。
         *
         * @param time 时间
         */
        public void setTime(LocalDateTime time) {
            this.time = time;
        }
    }

    /**
     * 未使用断言，避免静态分析误报。
     */
    @Test
    @DisplayName("构建器链式调用返回自身")
    void fluentReturnsSelf() {
        DataExportBuilder builder = Documents.of(sample());
        assertFalse(builder == null);
        assertEquals(builder, builder.format("xlsx"));
        assertEquals(builder, builder.engine("poi"));
        assertEquals(builder, builder.put("k", "v"));
    }
}