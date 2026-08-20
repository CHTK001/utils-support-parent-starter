package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 颜色 Mock 生成器
 *
 * <p>生成十六进制颜色值，如 {@code #3fb57a}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"color", "colour", "hex-color"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class ColorMockString implements MockString {

    /**
     * 十六进制字符池
     */
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    /**
     * 颜色值长度（# + 6 位）
     */
    private static final int LENGTH = 7;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(LENGTH);
        builder.append('#');
        for (int i = 1; i < LENGTH; i++) {
            builder.append(HEX[environment.nextInt(16)]);
        }
        return builder.toString();
    }
}