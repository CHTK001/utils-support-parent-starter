// [P3C A20 豁免] A20/A30/A31: 卡组织公开 BIN 号段等非业务魔法值场景，豁免。
package com.chua.common.support.mock;

import com.chua.common.support.spi.annotations.Spi;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * 信用卡号 Mock 生成器（随机信用卡）
 *
 * <p>仿「随机信用卡生成器」的通用口径：按卡组织（Visa / Mastercard / Amex /
 * Discover / JCB / Diners Club / 银联）的公开发卡前缀（BIN）生成位数正确、
 * 末位通过 Luhn（模 10）校验的虚拟卡号。可通过 {@link MockEnvironment#setKeyword(String)}
 * 指定卡组织（如 {@code visa} / {@code mastercard} / {@code amex} / {@code unionpay}），
 * 缺省在全部卡组织中随机。</p>
 *
 * <p>仅用于测试数据填充，不代表真实可用的银行卡号，禁止用于任何真实支付场景。</p>
 *
 * <p>本实现下沉在 common-starter（常用生成器随底层走，使用方无需依赖 mock-starter），
 * 通过 {@code META-INF/extensions/com.chua.common.support.mock.MockString} 手工登记。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Spi({"creditcard", "credit-card", "credit-card-no"})
public class CreditCardMockString implements MockString {

    /**
     * 卡组织定义：发卡前缀区间 + 卡号总位数
     */
    private enum Network {

        /**
         * Visa：4 开头，16 位
         */
        VISA(new long[][]{{4, 4, 1}}, 16),

        /**
         * Mastercard：51~55 或 2221~2720，16 位
         */
        MASTERCARD(new long[][]{{51, 55, 2}, {2221, 2720, 4}}, 16),

        /**
         * American Express：34 / 37，15 位
         */
        AMEX(new long[][]{{34, 34, 2}, {37, 37, 2}}, 15),

        /**
         * Discover：6011 / 644~649 / 65，16 位
         */
        DISCOVER(new long[][]{{6011, 6011, 4}, {644, 649, 3}, {65, 65, 2}}, 16),

        /**
         * JCB：3528~3589，16 位
         */
        JCB(new long[][]{{3528, 3589, 4}}, 16),

        /**
         * Diners Club：300~305 / 36 / 38，14 位
         */
        DINERS(new long[][]{{300, 305, 3}, {36, 36, 2}, {38, 38, 2}}, 14),

        /**
         * 银联 UnionPay：62 开头，16 位
         */
        UNIONPAY(new long[][]{{62, 62, 2}}, 16);

        /**
         * 发卡前缀区间：每项为 {min, max, 前缀位数}（min / max 为闭区间）
         */
        private final long[][] prefixRanges;

        /**
         * 卡号总位数（含 Luhn 校验位）
         */
        private final int length;

        Network(long[][] prefixRanges, int length) {
            this.prefixRanges = prefixRanges;
            this.length = length;
        }
    }

    /**
     * 全部卡组织（避免每次 getString 都 values() 分配新数组）
     */
    private static final Network[] NETWORKS = Network.values();

    @Override
    @Nonnull
    /**
     * get String
     * @param environment 参数
     * @return 返回值
     */
    public String getString(@Nonnull MockEnvironment environment) {
        Network network = resolve(environment.getKeyword());
        if (null == network) {
            network = NETWORKS[environment.nextInt(NETWORKS.length)];
        }
        long[] range = network.prefixRanges[environment.nextInt(network.prefixRanges.length)];
        long prefix = environment.nextLong(range[0], range[1] + 1);
        int prefixLength = (int) range[2];
        StringBuilder builder = new StringBuilder(network.length);
        builder.append(prefix);
        int bodyLength = network.length - prefixLength - 1;
        for (int i = 0; i < bodyLength; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.append(luhnCheckDigit(builder)).toString();
    }

    /**
     * 按关键词解析卡组织（大小写不敏感，支持常见别名）；未命中返回 null 表示随机。
     *
     * @param keyword 关键词
     * @return 卡组织；未指定或未命中时返回 {@code null}
     */
    private static Network resolve(String keyword) {
        if (null == keyword || keyword.isBlank()) {
            return null;
        }
        String token = keyword.trim().toLowerCase(Locale.ROOT);
        for (Network network : NETWORKS) {
            if (network.name().toLowerCase(Locale.ROOT).equals(token)) {
                return network;
            }
        }
        if (token.contains("master")) {
            return Network.MASTERCARD;
        }
        if (token.contains("amex") || token.contains("american")) {
            return Network.AMEX;
        }
        if (token.contains("discover")) {
            return Network.DISCOVER;
        }
        if (token.contains("jcb")) {
            return Network.JCB;
        }
        if (token.contains("diners")) {
            return Network.DINERS;
        }
        if (token.contains("union") || token.contains("yinlian") || token.contains("cup")) {
            return Network.UNIONPAY;
        }
        if (token.contains("visa")) {
            return Network.VISA;
        }
        return null;
    }

    /**
     * 根据现有数字位计算 Luhn 校验位。
     *
     * @param digits 不含校验位的数字串
     * @return 校验位字符
     */
    private static char luhnCheckDigit(StringBuilder digits) {
        int sum = 0;
        boolean alternate = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n <<= 1;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }
}
