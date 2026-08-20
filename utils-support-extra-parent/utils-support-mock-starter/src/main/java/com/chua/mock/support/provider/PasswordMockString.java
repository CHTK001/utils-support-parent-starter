package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 密码 Mock 生成器
 *
 * <p>生成包含大小写字母、数字与特殊字符的强密码，
 * 保证至少包含大写字母、小写字母、数字与特殊符号各一个，
 * 默认长度区间 [8, 20]。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("password")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class PasswordMockString implements MockString {

    /**
     * 小写字母池
     */
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    /**
     * 大写字母池
     */
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /**
     * 数字池
     */
    private static final String DIGITS = "0123456789";
    /**
     * 特殊符号池
     */
    private static final String SYMBOLS = "!@#$%^&*_-+=?";
    /**
     * 全部字符池
     */
    private static final String ALL = LOWER + UPPER + DIGITS + SYMBOLS;
    /**
     * 默认最短密码长度
     */
    private static final int DEFAULT_MIN = 8;
    /**
     * 默认最长密码长度
     */
    private static final int DEFAULT_MAX = 20;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int target = environment.length();
        int length = Math.max(target, DEFAULT_MIN);
        if (length > DEFAULT_MAX) {
            length = DEFAULT_MAX;
        }
        StringBuilder builder = new StringBuilder(length);
        builder.append(pick(environment, UPPER));
        builder.append(pick(environment, LOWER));
        builder.append(pick(environment, DIGITS));
        builder.append(pick(environment, SYMBOLS));
        for (int i = 4; i < length; i++) {
            builder.append(pick(environment, ALL));
        }
        return StringBuilderShuffler.shuffle(builder, environment);
    }

    /**
     * 从字符池中随机选取一个字符。
     *
     * @param environment Mock 环境
     * @param pool        字符池
     * @return 随机字符
     */
    private static char pick(@Nonnull MockEnvironment environment, @Nonnull String pool) {
        return pool.charAt(environment.nextInt(pool.length()));
    }

    /**
     * 简单的字符打乱器，避免固定前缀顺序泄露随机性。
     */
    private static final class StringBuilderShuffler {

        /**
         * 按随机顺序重排字符串。
         *
         * @param builder     待重排的字符串
         * @param environment Mock 环境
         * @return 重排后的字符串
         */
        static String shuffle(StringBuilder builder, MockEnvironment environment) {
            char[] chars = builder.toString().toCharArray();
            for (int i = chars.length - 1; i > 0; i--) {
                int j = environment.nextInt(i + 1);
                char tmp = chars[i];
                chars[i] = chars[j];
                chars[j] = tmp;
            }
            return new String(chars);
        }
    }
}