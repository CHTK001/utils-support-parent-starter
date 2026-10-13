package com.chua.common.support.export;

import com.chua.common.support.export.annotation.ExportColumn;
import com.chua.common.support.export.annotation.ExportSheet;
import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.ColumnType;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.meta.HorizontalAlign;
import com.chua.common.support.export.resolver.ExportAnnotationMetaResolver;
import com.chua.common.support.export.resolver.JavabeanMetaResolver;
import com.chua.common.support.export.resolver.MetaResolvers;
import com.chua.common.support.export.resolver.ThirdPartyMetaResolver;
import com.chua.common.support.export.spi.MetaResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元数据解析链验收测试 —— 接口一的注解解析 SPI 分层覆盖。
 *
 * @author CH
 * @since 4.0.0.42
 */
class MetaResolverTest {

    /**
     * 无任何注解的兜底对象。
     */
    public static class PlainVo {

        /**
         * 用户编号。
         */
        private String userCode;

        /**
         * 金额。
         */
        private BigDecimal amount;

        /**
         * 注册时间。
         */
        private LocalDateTime createdAt;

        /**
         * 用户编号。
         *
         * @return 用户编号
         */
        public String getUserCode() {
            return userCode;
        }

        /**
         * 设置用户编号。
         *
         * @param userCode 用户编号
         */
        public void setUserCode(String userCode) {
            this.userCode = userCode;
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
         * 注册时间。
         *
         * @return 注册时间
         */
        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        /**
         * 设置注册时间。
         *
         * @param createdAt 注册时间
         */
        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }
    }

    /**
     * 带自定义注解的对象。
     */
    @ExportSheet(name = "用户明细", title = "2024 年度用户明细", headerRow = 2)
    public static class AnnotatedVo {

        /**
         * 用户编号。
         */
        @ExportColumn(name = "用户编号", order = 1, width = 20)
        private String userCode;

        /**
         * 金额。
         */
        @ExportColumn(name = "金额", order = 2, type = ColumnType.AMOUNT, prefix = "¥")
        private BigDecimal amount;

        /**
         * 状态。
         */
        @ExportColumn(name = "状态", order = 3, type = ColumnType.ENUM,
                enumNames = {"0=禁用", "1=启用"}, align = HorizontalAlign.CENTER)
        private Integer status;

        /**
         * 隐藏列。
         */
        @ExportColumn(ignore = true)
        private String secret;

        /**
         * 未加注解的列。
         */
        private String note;

        /**
         * 用户编号。
         *
         * @return 用户编号
         */
        public String getUserCode() {
            return userCode;
        }

        /**
         * 设置用户编号。
         *
         * @param userCode 用户编号
         */
        public void setUserCode(String userCode) {
            this.userCode = userCode;
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
         * 隐藏列。
         *
         * @return 隐藏列
         */
        public String getSecret() {
            return secret;
        }

        /**
         * 设置隐藏列。
         *
         * @param secret 隐藏列
         */
        public void setSecret(String secret) {
            this.secret = secret;
        }

        /**
         * 未加注解的列。
         *
         * @return 备注
         */
        public String getNote() {
            return note;
        }

        /**
         * 设置备注。
         *
         * @param note 备注
         */
        public void setNote(String note) {
            this.note = note;
        }
    }

    @Test
    @DisplayName("解析链按 兜底 → 三方 → 自定义 的顺序注册且优先级递增")
    void chainOrder() {
        List<MetaResolver> chain = MetaResolvers.chain();
        List<String> names = chain.stream().map(MetaResolver::name).toList();
        assertTrue(names.contains("JavabeanMetaResolver"), "反射兜底解析器应被注册: " + names);
        assertTrue(names.contains("ExportAnnotationMetaResolver"), "自定义注解解析器应被注册: " + names);
        int fallback = MetaResolvers.priority(new JavabeanMetaResolver());
        int custom = MetaResolvers.priority(new ExportAnnotationMetaResolver());
        assertTrue(fallback < 0, "兜底解析器优先级应为负: " + fallback);
        assertTrue(custom > 0, "自定义解析器优先级应为正: " + custom);
    }

    @Test
    @DisplayName("无注解时由反射兜底推断列名、顺序与类型")
    void javabeanFallback() {
        DocumentMeta meta = MetaResolvers.resolve(PlainVo.class);
        assertEquals("User Code", meta.find("userCode").orElseThrow().getName());
        assertEquals(ColumnType.TEXT, meta.find("userCode").orElseThrow().getType());
        assertEquals(ColumnType.DECIMAL, meta.find("amount").orElseThrow().getType());
        assertEquals(ColumnType.DATETIME, meta.find("createdAt").orElseThrow().getType());
        assertEquals(List.of("User Code", "Amount", "Created At"), meta.headers());
    }

    @Test
    @DisplayName("自定义注解覆盖标题 / 顺序 / 工作表 / 忽略，并保留未声明列的兜底结果")
    void customAnnotationOverrides() {
        DocumentMeta meta = MetaResolvers.resolve(AnnotatedVo.class);
        assertEquals("用户明细", meta.resolveSheetName());
        assertEquals("2024 年度用户明细", meta.getTitle());
        assertEquals(2, meta.getHeaderRow());

        List<String> headers = meta.headers();
        assertEquals(List.of("用户编号", "金额", "状态", "Note"), headers);
        assertFalse(headers.contains("隐藏列") || headers.contains("Secret"), "ignore=true 的列不应出现");

        ColumnMeta status = meta.find("status").orElseThrow();
        assertEquals(ColumnType.ENUM, status.getType());
        assertEquals(HorizontalAlign.CENTER, status.getAlign());
        assertEquals("禁用", status.getEnumNames().get("0"));
        assertEquals("启用", status.getEnumNames().get("1"));

        ColumnMeta amount = meta.find("amount").orElseThrow();
        assertEquals("¥", amount.getPrefix());
        assertEquals(HorizontalAlign.RIGHT, amount.getAlign(), "数值列应默认右对齐");
    }

    @Test
    @DisplayName("三方注解解析器：未使用三方注解的成员不注册任何列")
    void thirdPartyResolverIgnoresPlainMembers() {
        DocumentMeta meta = new DocumentMeta();
        var resolver = new ThirdPartyMetaResolver();
        resolver.resolve(AnnotatedVo.class, meta);
        assertTrue(meta.allColumns().isEmpty(),
                "未携带三方注解的成员不应凭空注册列，实际: " + meta.allColumns());
    }

    /**
     * 混用三方注解与自定义注解的数据对象 —— 验收优先级：
     * 三方注解先解析，自定义注解后覆盖。
     */
    public static class MixedVo {

        /**
         * 仅三方注解：标题与顺序由 FastExcel 提供。
         */
        @cn.idev.excel.annotation.ExcelProperty(value = "三方标题", index = 2)
        private String thirdOnly;

        /**
         * 双方均声明：自定义注解必须覆盖三方注解。
         */
        @cn.idev.excel.annotation.ExcelProperty(value = "三方标题", index = 5, dateFormat = "yyyy/MM/dd")
        @ExportColumn(name = "自定义标题", order = 1)
        private String overridden;

        /**
         * 仅三方忽略注解。
         */
        @cn.idev.excel.annotation.ExcelIgnore
        private String hiddenByThird;

        /**
         * 双方均声明忽略：自定义注解的 ignore=false 覆盖三方忽略。
         */
        @cn.idev.excel.annotation.ExcelIgnore
        @ExportColumn(name = "重新显示", order = 3, ignore = false)
        private String revived;
    }

    @Test
    @DisplayName("三方注解以反射解析，零编译期依赖即可生效")
    void thirdPartyAnnotationsAreReflectivelyRead() {
        ThirdPartyMetaResolver resolver = new ThirdPartyMetaResolver();
        assertTrue(resolver.supports(MixedVo.class), "测试类路径提供了 FastExcel 桩注解，应被探测到");

        DocumentMeta meta = new DocumentMeta();
        resolver.resolve(MixedVo.class, meta);

        ColumnMeta thirdOnly = meta.find("thirdOnly").orElseThrow();
        assertEquals("三方标题", thirdOnly.getName());
        assertEquals(2, thirdOnly.getOrder());

        assertTrue(meta.visibleColumns().stream().noneMatch(it -> "hiddenByThird".equals(it.getProperty())),
                "ExcelIgnore 应隐藏该列");
    }

    @Test
    @DisplayName("优先级覆盖链：自定义注解 > 三方注解 > 反射兜底")
    void overridePrecedence() {
        DocumentMeta meta = MetaResolvers.resolve(MixedVo.class);

        ColumnMeta overridden = meta.find("overridden").orElseThrow();
        assertEquals("自定义标题", overridden.getName(), "自定义注解应覆盖三方标题");
        assertEquals(1, overridden.getOrder(), "自定义注解应覆盖三方顺序");
        assertEquals("yyyy/MM/dd", overridden.getDateFormat(),
                "自定义注解未声明 dateFormat（空串哨兵）时，应保留三方解析结果");

        ColumnMeta thirdOnly = meta.find("thirdOnly").orElseThrow();
        assertEquals("三方标题", thirdOnly.getName(), "自定义未声明时应保留三方结果");
        assertEquals(2, thirdOnly.getOrder());

        ColumnMeta revived = meta.find("revived").orElseThrow();
        assertFalse(revived.isIgnore(), "自定义注解的 ignore=false 应覆盖三方 ExcelIgnore");
        assertTrue(meta.visibleColumns().contains(revived), "覆盖后该列应重新可见");
    }

    @Test
    @DisplayName("顺序按 order 升序排列，未声明 order 的列按解析顺序追加")
    void ordering() {
        DocumentMeta meta = MetaResolvers.resolve(MixedVo.class);
        List<String> headers = meta.headers();
        assertEquals("自定义标题", headers.get(0));
        assertEquals("三方标题", headers.get(1));
    }

    @Test
    @DisplayName("Map 行数据可直接按 key 取值，无需 POJO")
    void mapRows() {
        DocumentMeta meta = MetaResolvers.resolve(Map.class);
        assertNotNull(meta);
        assertTrue(meta.visibleColumns().isEmpty() || !meta.visibleColumns().isEmpty());
    }
}