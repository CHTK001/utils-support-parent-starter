package com.chua.common.support.utils;

import java.util.regex.Pattern;

/**
 * 通用格式校验工具类。
 *
 * <p>提供与业务无关的常见字面量格式校验：中国手机号、邮箱、18 位身份证号、
 * IPv4 地址、URL，以及纯数字、纯字母、字母数字混合、是否含中文等字符集判断。
 * 本类为纯静态工具类，禁止实例化。</p>
 *
 * <p>设计约定：全部方法接受 {@code null} 并返回 {@code false}，不抛异常；
 * 入参在匹配前统一 {@code trim()}，避免脏数据里的首尾空白导致误判。
 * 所有正则在类加载时预编译（P3C 1.9.1），不在方法内重复编译。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Validator {

    /**
     * 中国内地手机号正则：1 开头，第二位 3-9，共 11 位。
     */
    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /**
     * 邮箱正则：本地部分允许字母数字与 {@code . _ % + -}，域名至少两段。
     */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    /**
     * 18 位身份证正则：地区码 6 位 + 出生日期 8 位 + 顺序码 3 位 + 校验位 1 位。
     */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
            "^[1-9]\\d{5}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$");

    /**
     * IPv4 正则：四段 0-255，以点分隔。
     */
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.(25[0-5]|2[0-4]\\d|" +
                    "[01]?\\d\\d?)\\.(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");

    /**
     * HTTP/HTTPS 协议前缀常量，用于 URL 快速判定。
     */
    private static final String HTTP_PREFIX = "http://";

    /**
     * HTTPS 协议前缀常量，用于 URL 快速判定。
     */
    private static final String HTTPS_PREFIX = "https://";

    /**
     * 工具类禁止实例化。
     */
    private Validator() {
    }

    /**
     * 校验中国内地手机号格式。
     *
     * @param phone 待校验手机号，为 null 时返回 false
     * @return 格式正确返回 true，否则返回 false
     */
    public static boolean isMobile(String phone) {
        if (phone == null) {
            return false;
        }
        return MOBILE_PATTERN.matcher(phone.trim()).matches();
    }

    /**
     * 校验邮箱格式。
     *
     * @param email 待校验邮箱地址，为 null 时返回 false
     * @return 格式正确返回 true，否则返回 false
     */
    public static boolean isEmail(String email) {
        if (email == null) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    /**
     * 校验 18 位身份证号格式。
     *
     * <p>仅做格式校验，不校验校验位与地区码真实性。</p>
     *
     * @param idCard 待校验身份证号，为 null 时返回 false
     * @return 格式正确返回 true，否则返回 false
     */
    public static boolean isIdCard(String idCard) {
        if (idCard == null) {
            return false;
        }
        return ID_CARD_PATTERN.matcher(idCard.trim()).matches();
    }

    /**
     * 校验 IPv4 地址格式。
     *
     * @param ip 待校验 IP 地址，为 null 时返回 false
     * @return 格式正确返回 true，否则返回 false
     */
    public static boolean isIp(String ip) {
        if (ip == null) {
            return false;
        }
        return IP_PATTERN.matcher(ip.trim()).matches();
    }

    /**
     * 校验字符串是否全部由十进制数字字符组成。
     *
     * <p>语义与 {@link NumberUtils#isNumber(CharSequence)} 完全一致，
     * 内部直接委托以避免两套实现漂移；需要接受小数、负数、科学计数法时
     * 请改用 {@link NumberUtils#isDecimalNumber(CharSequence)}。</p>
     *
     * @param str 待校验字符串，为 null 或空串时返回 false
     * @return 全部为 0-9 数字字符且非空返回 true，否则返回 false
     */
    public static boolean isNumeric(String str) {
        return NumberUtils.isNumber(str);
    }

    /**
     * 校验字符串是否全部由字母组成。
     *
     * @param str 待校验字符串，为 null 或空串时返回 false
     * @return 全部为字母且非空返回 true，否则返回 false
     */
    public static boolean isAlpha(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isLetter(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验字符串是否由字母与数字混合组成。
     *
     * @param str 待校验字符串，为 null 或空串时返回 false
     * @return 只包含字母或数字且非空返回 true，否则返回 false
     */
    public static boolean isAlphanumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isLetterOrDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验字符串是否为 HTTP/HTTPS 协议开头的 URL。
     *
     * <p>轻量判定：只判断协议前缀，不做语法解析，也不校验可达性。</p>
     *
     * @param url 待校验 URL，为 null 时返回 false
     * @return 以 http:// 或 https:// 开头返回 true，否则返回 false
     */
    public static boolean isUrl(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim().toLowerCase();
        return trimmed.startsWith(HTTP_PREFIX) || trimmed.startsWith(HTTPS_PREFIX);
    }

    /**
     * 判断字符串中是否包含中文字符（Han 脚本）。
     *
     * <p>逐字符用 {@link Character.UnicodeScript#HAN} 判定，覆盖 CJK 统一表意文字
     * 及扩展区，不依赖硬编码码点区间。常用于区分"需要按中文回显的提示信息"。</p>
     *
     * @param str 待判断字符串，为 null 时返回 false
     * @return 至少包含一个中文字符返回 true，否则返回 false
     */
    public static boolean hasChinese(String str) {
        if (str == null) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}
