package com.chua.common.support.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NumberUtils#isDecimalNumber(CharSequence)} 与
 * {@link NumberUtils#normalizeNumberLiteral(CharSequence)} 的语义锁定测试。
 *
 * <p>这两个方法是为消除各业务模块各自实现的"字符串是不是数字"判断而生
 * （redis-starter 曾自建 NumberUtils，行为与 common-starter 的
 * {@code isNumber} 不同：前者接受小数与负数，后者只认纯数字字符）。
 * 它们被用作"先判断再 new BigDecimal"的前置条件，一旦判断口径与
 * {@link BigDecimal#BigDecimal(String)} 的接受范围不一致，
 * 就会出现"判断通过但转换抛 NumberFormatException"的线上故障，
 * 因此这里逐条钉住边界：整数、小数、负数、科学计数法、千分位、
 * 空白、空串、null、NaN/Infinity。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("数值字面量判断与规范化")
class NumberUtilsDecimalTest {

    /**
     * 合法十进制字面量必须被识别为数值。
     */
    @Test
    @DisplayName("整数/小数/负数/科学计数法都应判为数值")
    void decimalLiteralsAreAccepted() {
        String[] accepted = {
                "123", "-3", "+7", "12.5", ".5", "233.",
                "1e5", "1.5e-3", "2E+10", "0", "-0.75"
        };
        for (String literal : accepted) {
            assertTrue(NumberUtils.isDecimalNumber(literal), "应判为数值: " + literal);
        }
    }

    /**
     * 非数值字面量必须被拒绝，尤其是空串与 NaN/Infinity。
     */
    @Test
    @DisplayName("空串/null/NaN/Infinity/字母都应判为非数值")
    void nonDecimalLiteralsAreRejected() {
        String[] rejected = {"", "   ", "NaN", "Infinity", "-Infinity", "12f", "abc", "1.2.3", "--1", "1e"};
        for (String literal : rejected) {
            assertFalse(NumberUtils.isDecimalNumber(literal), "不应判为数值: " + literal);
        }
        assertFalse(NumberUtils.isDecimalNumber(null), "null 不应判为数值");
    }

    /**
     * 千分位与空白属于格式化修饰，剔除后仍应判为数值。
     */
    @Test
    @DisplayName("千分位逗号与空白剔除后应判为数值")
    void groupedLiteralsAreAccepted() {
        assertTrue(NumberUtils.isDecimalNumber("1,234"));
        assertTrue(NumberUtils.isDecimalNumber(" 12 "));
        assertTrue(NumberUtils.isDecimalNumber("1,234.56"));
    }

    /**
     * 规范化结果必须与 BigDecimal 的接受范围一致，且同一字符串可直接转换。
     */
    @Test
    @DisplayName("规范化后可直接构造 BigDecimal，不抛异常")
    void normalizedLiteralIsParseable() {
        String[] grouped = {"1,234", " 12 ", "1,234.56", "-3", "1.5e-3"};
        for (String raw : grouped) {
            String normalized = NumberUtils.normalizeNumberLiteral(raw);
            assertTrue(NumberUtils.isDecimalNumber(normalized), "规范化后应判为数值: " + raw);
            assertDoesNotThrow(() -> new BigDecimal(normalized), "规范化后应可解析: " + raw);
        }
        assertEquals("1234", NumberUtils.normalizeNumberLiteral("1,234"));
        assertEquals("", NumberUtils.normalizeNumberLiteral(null));
    }

    /**
     * 原 isNumber 语义保持不变：只认纯数字字符。
     */
    @Test
    @DisplayName("isNumber 仍只认纯数字字符，语义不被 isDecimalNumber 改变")
    void isNumberKeepsStrictSemantics() {
        assertTrue(NumberUtils.isNumber("123"));
        assertFalse(NumberUtils.isNumber("12.5"));
        assertFalse(NumberUtils.isNumber("-3"));
        assertFalse(NumberUtils.isNumber("1e5"));
        assertFalse(NumberUtils.isNumber(null));
    }

    /**
     * QPS 汇总场景：混合合法与非法值，只累加合法数值。
     */
    @Test
    @DisplayName("批量字面量按口径累加，非法值被跳过")
    void aggregateOnlyCountsDecimals() {
        String[] values = {"10", "2.5", "abc", "1,000", "", "3e2"};
        BigDecimal sum = BigDecimal.ZERO;
        for (String raw : values) {
            String normalized = NumberUtils.normalizeNumberLiteral(raw);
            if (NumberUtils.isDecimalNumber(normalized)) {
                sum = sum.add(new BigDecimal(normalized));
            }
        }
        assertEquals(0, new BigDecimal("1312.5").compareTo(sum), "10 + 2.5 + 1000 + 300 = 1312.5");
    }
}
