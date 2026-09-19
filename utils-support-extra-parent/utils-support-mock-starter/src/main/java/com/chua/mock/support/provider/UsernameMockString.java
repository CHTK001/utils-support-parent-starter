package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 用户名 Mock 生成器
 *
 * <p>由小写字母与数字组成，默认长度区间 [6, 16]；
 * 若环境指定了该区间外的长度，将按默认区间约束取值。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("username")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class UsernameMockString implements MockString {

    /**
     * 用户名字符池（小写字母 + 数字）
     */
    private static final char[] CHARS =
            "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    /**
     * 默认最短用户名长度
     */
    private static final int DEFAULT_MIN = 6;
    /**
     * 默认最长用户名长度
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final int DEFAULT_MAX = 16;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int min = Math.max(environment.getMinLength(), DEFAULT_MIN);
        int max = Math.min(Math.max(environment.getMaxLength(), min), DEFAULT_MAX);
        int length = environment.nextInt(min, max + 1);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(CHARS[environment.nextInt(CHARS.length)]);
        }
        return builder.toString();
    }
}
