package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 数字 Mock 生成器
*
* <p>按环境指定长度生成由数字 0-9 组成的字符串，如 {@code 18472305}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"digit", "digits", "number"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class DigitMockString implements MockString {

    /**
    * 数字池
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final char[] DIGITS = "0123456789".toCharArray();

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int length = environment.length();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(DIGITS[environment.nextInt(DIGITS.length)]);
        }
        return builder.toString();
    }
}