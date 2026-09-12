package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 验证码 Mock 生成器
 *
 * <p>生成 4-6 位验证码（数字 + 大写字母，剔除易混淆字符），
 * 默认 4 位，如 {@code 3K7P}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"captcha", "verify-code", "verification-code"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class CaptchaMockString implements MockString {

    /**
     * 验证码字符池（剔除 0/O/1/I/L 等易混淆字符）
     */
    private static final char[] CHARS =
            "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    /**
     * 默认验证码长度
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final int DEFAULT_LENGTH = 4;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int length = environment.length();
        if (length < 4 || length > 6) {
            length = DEFAULT_LENGTH;
        }
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(CHARS[environment.nextInt(CHARS.length)]);
        }
        return builder.toString();
    }
}