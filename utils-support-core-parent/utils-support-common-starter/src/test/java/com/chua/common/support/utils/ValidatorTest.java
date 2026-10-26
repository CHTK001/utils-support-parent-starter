package com.chua.common.support.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Validator} 的语义锁定测试。
 *
 * <p>{@code Validator} 由 spring 侧三份重复实现（全限定同名的死代码版、
 * 仅含 {@code hasChinese} 的简化版、以及本次迁移的完整版）合并而来，
 * 现在是各业务模块共用的唯一实现。合并过程最容易出现语义漂移，
 * 尤其是 {@code hasChinese} 的判定范围（旧实现用 {@code \u4E00-\u9FFF}
 * 硬编码区间，新实现用 {@link Character.UnicodeScript#HAN}），
 * 因此这里逐项钉住边界。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("通用格式校验")
class ValidatorTest {

    /**
     * 手机号校验：合法号码通过，长度或号段错误被拒。
     */
    @Test
    @DisplayName("手机号格式校验")
    void mobileCheck() {
        assertTrue(Validator.isMobile("13800138000"));
        assertTrue(Validator.isMobile(" 19912345678 "), "应容忍首尾空白");
        assertFalse(Validator.isMobile("12345678901"), "第二位为 2 非法");
        assertFalse(Validator.isMobile("1380013800"), "位数为 10 非法");
        assertFalse(Validator.isMobile(null));
    }

    /**
     * 邮箱校验：常规邮箱通过，缺域名或本地部分被拒。
     */
    @Test
    @DisplayName("邮箱格式校验")
    void emailCheck() {
        assertTrue(Validator.isEmail("user@example.com"));
        assertTrue(Validator.isEmail("a.b+c@sub.example.cn"));
        assertFalse(Validator.isEmail("user@"), "缺少域名");
        assertFalse(Validator.isEmail("@example.com"), "缺少本地部分");
        assertFalse(Validator.isEmail(null));
    }

    /**
     * 身份证校验：格式正确通过，位数或日期非法被拒。
     */
    @Test
    @DisplayName("身份证号格式校验")
    void idCardCheck() {
        assertTrue(Validator.isIdCard("110101199003074512"));
        assertTrue(Validator.isIdCard("11010119900307451X"), "末位 X 合法");
        assertFalse(Validator.isIdCard("11010119900307451"), "17 位非法");
        assertFalse(Validator.isIdCard("110101199013074512"), "月份 13 非法");
        assertFalse(Validator.isIdCard(null));
    }

    /**
     * IPv4 校验：合法地址通过，超出 255 或段数不足被拒。
     */
    @Test
    @DisplayName("IPv4 格式校验")
    void ipCheck() {
        assertTrue(Validator.isIp("192.168.1.1"));
        assertTrue(Validator.isIp("0.0.0.0"));
        assertFalse(Validator.isIp("256.1.1.1"), "段值超界");
        assertFalse(Validator.isIp("192.168.1"), "段数不足");
        assertFalse(Validator.isIp(null));
    }

    /**
     * 字符集校验：数字、字母、字母数字三类互斥边界。
     */
    @Test
    @DisplayName("数字/字母/字母数字校验")
    void charsetChecks() {
        assertTrue(Validator.isNumeric("123456"));
        assertFalse(Validator.isNumeric("12.5"), "小数点不属于纯数字");
        assertFalse(Validator.isNumeric(""), "空串非数字");
        assertTrue(Validator.isAlpha("abcXYZ"));
        assertFalse(Validator.isAlpha("abc1"));
        assertTrue(Validator.isAlphanumeric("abc123"));
        assertFalse(Validator.isAlphanumeric("abc-123"), "连字符不属于字母数字");
    }

    /**
     * URL 校验：仅判定协议前缀。
     */
    @Test
    @DisplayName("URL 协议前缀校验")
    void urlCheck() {
        assertTrue(Validator.isUrl("http://example.com"));
        assertTrue(Validator.isUrl("HTTPS://example.com"), "应忽略大小写");
        assertFalse(Validator.isUrl("ftp://example.com"));
        assertFalse(Validator.isUrl(null));
    }

    /**
     * 中文判定：覆盖简体、繁体与 null/空串边界。
     */
    @Test
    @DisplayName("中文字符判定")
    void chineseCheck() {
        assertTrue(Validator.hasChinese("登录失败"));
        assertTrue(Validator.hasChinese("繁體中文"), "繁体同样属于 Han 脚本");
        assertTrue(Validator.hasChinese("error 登录"), "混合文本中只要有中文即为真");
        assertFalse(Validator.hasChinese("login failed"));
        assertFalse(Validator.hasChinese(""));
        assertFalse(Validator.hasChinese(null));
    }
}
