package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 随机字符串 Mock 生成器
*
* <p>从字母（大小写）与数字组成的字符池中按环境指定的长度随机抽取字符，
* 作为 mock字符串 SPI 的默认实现（{@code @SpiDefault}）。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi(value = {"random", "string"}, order = -100)
@SpiDefault
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class RandomMockString implements MockString {

    /**
    * 随机字符池（小写字母 + 大写字母 + 数字）
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final char[] CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int length = environment.length();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(CHARS[environment.nextInt(CHARS.length)]);
        }
        return builder.toString();
    }
}
