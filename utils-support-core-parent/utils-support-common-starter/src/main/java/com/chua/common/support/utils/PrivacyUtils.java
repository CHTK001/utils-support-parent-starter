package com.chua.common.support.utils;


/**
 * 隐私脱敏工具类
 *
 * <p>对敏感信息（姓名、手机号、身份证、银行卡、邮箱、地址、车牌等）进行掩码处理，
 * 保留关键前缀/后缀，中间用占位符替换。</p>
 *
 * <h2>能力矩阵</h2>
 * <table border="1">
 *   <tr><th>能力</th><th>方法</th><th>典型场景</th></tr>
 *   <tr><td>通用脱敏</td><td>{@link #desValue(String, int, int, String)}</td><td>保留前 N 后 M 位</td></tr>
 *   <tr><td>中文姓名</td><td>{@link #hideChineseName(String)}</td><td>张三 → 张*</td></tr>
 *   <tr><td>身份证/银行卡</td><td>{@link #hideCard(String)} / {@link #hideBankCard(String)}</td><td>保留前 4 后 4</td></tr>
 *   <tr><td>手机号</td><td>{@link #hidePhone(String)}</td><td>保留前 3 后 4</td></tr>
 *   <tr><td>邮箱</td><td>{@link #hideEmail(String)}</td><td>保留首字符与域名</td></tr>
 *   <tr><td>地址</td><td>{@link #hideAddress(String, int)}</td><td>保留前 N 位，后敏感位脱敏</td></tr>
 *   <tr><td>密码</td><td>{@link #hidePassword(String)}</td><td>统一返回 ******</td></tr>
 *   <tr><td>车牌</td><td>{@link #hideCarNumber(String)}</td><td>保留前 2 后 2</td></tr>
 * </table>
 *
 * <h2>线程安全</h2>
 * <p>所有方法均为静态方法且无共享状态，线程安全。</p>
 *
 * @since 4.0.0.42
 */
public final class PrivacyUtils {

    /**
     * 默认掩码占位符
     */
    private static final String DEFAULT_MASK = "*";

    /**
     * 密码统一返回的固定字符串
     */
    private static final String PASSWORD_MASK = "******";

    /**
     * 私有构造方法，防止实例化
     */
    private PrivacyUtils() {
    }

    /**
     * 通用脱敏方法：保留原字符串前 {@code prefixNoMaskLen} 位与后 {@code suffixNoMaskLen} 位，
     * 中间部分用 {@code symbol} 填充。
     *
     * <p>当 {@code prefix + suffix >= length} 时认为无需脱敏，直接返回原字符串。</p>
     *
     * @param origin           原始字符串
     * @param prefixNoMaskLen  保留前缀长度
     * @param suffixNoMaskLen  保留后缀长度
     * @param symbol           掩码占位符，为空时默认使用 "*"
     * @return 脱敏后的字符串；入参为 null 时返回 null
     */
    public static String desValue(String origin, int prefixNoMaskLen, int suffixNoMaskLen, String symbol) {
        if (origin == null) {
            return null;
        }
        int length = origin.length();
        if (length == 0) {
            return origin;
        }
        int prefix = Math.max(0, prefixNoMaskLen);
        int suffix = Math.max(0, suffixNoMaskLen);
        if (prefix + suffix >= length) {
            return origin;
        }
        String mask = (symbol == null || symbol.isEmpty()) ? DEFAULT_MASK : symbol;
        StringBuilder builder = new StringBuilder();
        builder.append(origin, 0, prefix);
        builder.append(mask.repeat(length - prefix - suffix));
        if (suffix > 0) {
            builder.append(origin, length - suffix, length);
        }
        return builder.toString();
    }

    /**
     * 中文姓名脱敏：保留首字符，其余用 "*" 替换。
     *
     * <p>例：张三 → 张*；欧阳修 → 欧**</p>
     *
     * @param fullName 原始姓名
     * @return 脱敏后的姓名；入参为空时原样返回
     */
    public static String hideChineseName(String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            return fullName;
        }
        if (fullName.length() == 1) {
            return DEFAULT_MASK;
        }
        return fullName.charAt(0) + DEFAULT_MASK.repeat(fullName.length() - 1);
    }

    /**
     * 身份证/银行卡脱敏：保留前 4 位与后 4 位，中间用 "*" 填充。
     *
     * @param cardNo 卡号
     * @return 脱敏后的卡号
     */
    public static String hideCard(String cardNo) {
        return desValue(cardNo, 4, 4, DEFAULT_MASK);
    }

    /**
     * 银行卡脱敏：保留前 4 位与后 4 位，中间用 "*" 填充。
     *
     * <p>本方法为 {@link #hideCard(String)} 的语义别名，便于业务代码语义表达。</p>
     *
     * @param cardNo 银行卡号
     * @return 脱敏后的银行卡号
     */
    public static String hideBankCard(String cardNo) {
        return desValue(cardNo, 4, 4, DEFAULT_MASK);
    }

    /**
     * 手机号脱敏：保留前 3 位与后 4 位，中间用 "*" 填充。
     *
     * <p>例：13812345678 → 138****5678</p>
     *
     * @param phone 原始手机号
     * @return 脱敏后的手机号
     */
    public static String hidePhone(String phone) {
        return desValue(phone, 3, 4, DEFAULT_MASK);
    }

    /**
     * 邮箱脱敏：保留首字符与域名部分，中间用 "****" 替换。
     *
     * <p>例：user@example.com → u****@example.com</p>
     *
     * @param email 原始邮箱
     * @return 脱敏后的邮箱
     */
    public static String hideEmail(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return desValue(email, 1, 0, DEFAULT_MASK);
        }
        return email.charAt(0) + "****" + email.substring(at);
    }

    /**
     * 地址脱敏：保留前 {@code length - sensitiveSize} 位，敏感部分用 "*" 替换。
     *
     * @param address       原始地址
     * @param sensitiveSize 敏感位数（即末尾需要脱敏的字符数）
     * @return 脱敏后的地址
     */
    public static String hideAddress(String address, int sensitiveSize) {
        if (address == null || address.isEmpty()) {
            return address;
        }
        int keep = Math.max(0, address.length() - Math.max(0, sensitiveSize));
        return desValue(address, keep, 0, DEFAULT_MASK);
    }

    /**
     * 密码脱敏：统一返回固定字符串 "******"。
     *
     * @param password 原始密码
     * @return 固定脱敏字符串
     */
    public static String hidePassword(String password) {
        if (password == null || password.isEmpty()) {
            return password;
        }
        return PASSWORD_MASK;
    }

    /**
     * 车牌号脱敏：保留前 2 位与后 2 位，中间用 "*" 填充。
     *
     * <p>例：京A12345 → 京A***45</p>
     *
     * @param carNumber 原始车牌号
     * @return 脱敏后的车牌号
     */
    public static String hideCarNumber(String carNumber) {
        return desValue(carNumber, 2, 2, DEFAULT_MASK);
    }
}
