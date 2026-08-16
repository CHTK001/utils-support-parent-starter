package com.chua.filestorage.support.storage.lanzou;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蓝奏云前置反爬挑战（acw_sc__v2）求解器。
 *
 * <p>蓝奏云站点部署在阿里云 WAF 之后。首次访问分享页时，服务端不会直接返回业务 HTML，
 * 而是返回一段混淆 JS，其中携带一个 40 位十六进制的 {@code arg1} 参数。浏览器执行该 JS
 * 后会计算出 {@code acw_sc__v2} Cookie 并自动重载页面，携带该 Cookie 的第二次请求才会
 * 返回真正的业务页面。</p>
 *
 * <p>本类以纯 Java 复现该计算过程，避免引入 JS 引擎：</p>
 * <ol>
 *   <li>从 HTML 中提取 {@code arg1}（形如 {@code var arg1='B2DAA420...'}）；</li>
 *   <li>{@link #unsbox(String)}：按固定位置表对 40 个字符做逆置换还原；</li>
 *   <li>{@link #hexXor(String, String)}：与固定密钥按字节异或；</li>
 *   <li>异或结果即为 {@code acw_sc__v2} 的值。</li>
 * </ol>
 *
 * <p>识别方式：响应 HTML 中同时包含 {@code arg1=} 与 {@code acw_sc__v2} 标记，
 * 且不含正常业务页面特征时，即判定为挑战页。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class LanzouAntiCrawler {

    /**
     * 位置置换表，长度 40，对应 arg1 的 40 个十六进制字符。
     */
    private static final int[] POSITION_TABLE = {
            0xF, 0x23, 0x1D, 0x18, 0x21, 0x10, 0x1, 0x26,
            0xA, 0x9, 0x13, 0x1F, 0x28, 0x1B, 0x16, 0x17,
            0x19, 0xD, 0x6, 0xB, 0x27, 0x12, 0x14, 0x8,
            0xE, 0x15, 0x20, 0x1A, 0x2, 0x1E, 0x7, 0x4,
            0x11, 0x5, 0x3, 0x1C, 0x22, 0x25, 0xC, 0x24
    };

    /**
     * 异或密钥，由 WAF 脚本硬编码。
     */
    private static final String XOR_KEY = "3000176000856006061501533003690027800375";

    /**
     * arg1 提取正则，兼容单双引号。
     */
    private static final Pattern ARG1_PATTERN =
            Pattern.compile("arg1\\s*=\\s*['\"]([0-9A-Fa-f]+)['\"]");

    private LanzouAntiCrawler() {
    }

    /**
     * 判断给定 HTML 是否为 WAF 挑战页。
     *
     * @param html 响应正文
     * @return true 表示需要计算 acw_sc__v2 后重试
     */
    static boolean isChallenge(String html) {
        if (html == null || html.isEmpty()) {
            return false;
        }
        return html.contains("acw_sc__v2") && ARG1_PATTERN.matcher(html).find();
    }

    /**
     * 求解挑战，返回 acw_sc__v2 Cookie 值。
     *
     * @param html 挑战页 HTML
     * @return acw_sc__v2 值；无法解析时返回 null
     */
    static String resolve(String html) {
        if (html == null) {
            return null;
        }
        Matcher matcher = ARG1_PATTERN.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        String arg1 = matcher.group(1);
        if (arg1.length() != POSITION_TABLE.length) {
            return null;
        }
        return hexXor(unsbox(arg1), XOR_KEY);
    }

    /**
     * 按位置表还原被打乱的字符串。
     *
     * <p>置换规则：原串第 i 个字符应放置到结果的 {@code POSITION_TABLE[i] - 1} 位。</p>
     *
     * @param arg 长度为 40 的十六进制串
     * @return 还原后的字符串
     */
    private static String unsbox(String arg) {
        char[] result = new char[POSITION_TABLE.length];
        for (int i = 0; i < POSITION_TABLE.length; i++) {
            result[POSITION_TABLE[i] - 1] = arg.charAt(i);
        }
        return new String(result);
    }

    /**
     * 按字节（每两个十六进制字符）对输入与密钥做异或。
     *
     * <p>输出长度取两者较短者，结果为小写十六进制串，单字符结果左补 0。</p>
     *
     * @param input 输入十六进制串
     * @param key   密钥十六进制串
     * @return 异或后的十六进制串
     */
    private static String hexXor(String input, String key) {
        int length = Math.min(input.length(), key.length());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i + 2 <= length; i += 2) {
            int left = Integer.parseInt(input.substring(i, i + 2), 16);
            int right = Integer.parseInt(key.substring(i, i + 2), 16);
            String hex = Integer.toHexString(left ^ right);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }
}
