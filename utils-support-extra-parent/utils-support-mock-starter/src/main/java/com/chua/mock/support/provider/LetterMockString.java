package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 英文字母 Mock 生成器
*
* <p>在大写与小写英文字母池内按环境指定长度随机抽取，如 {@code aBcDeFg}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"letter", "letters"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class LetterMockString implements MockString {

    /**
    * 英文字母池（大小写）
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final char[] LETTERS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int length = environment.length();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(LETTERS[environment.nextInt(LETTERS.length)]);
        }
        return builder.toString();
    }
}