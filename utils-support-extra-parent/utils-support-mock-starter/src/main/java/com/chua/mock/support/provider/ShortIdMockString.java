package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 短 标识 Mock 生成器
*
* <p>生成 6-12 位 base62 短 ID（大小写字母 + 数字），
* 常用于短链接或短主键，如 {@code a9Kx3mQ}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"short-id", "shortid", "short-code"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class ShortIdMockString implements MockString {

    /**
    * 基础62 字符池
    */
    private static final char[] CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    /**
    * 默认长度下界
    */
    private static final int DEFAULT_MIN = 6;
    /**
    * 默认长度上界
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final int DEFAULT_MAX = 12;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int length = environment.length();
        if (length < DEFAULT_MIN || length > DEFAULT_MAX) {
            length = environment.nextInt(DEFAULT_MIN, DEFAULT_MAX + 1);
        }
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(CHARS[environment.nextInt(CHARS.length)]);
        }
        return builder.toString();
    }
}
