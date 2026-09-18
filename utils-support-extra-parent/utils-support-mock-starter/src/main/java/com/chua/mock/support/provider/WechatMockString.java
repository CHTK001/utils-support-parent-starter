package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 微信号 Mock 生成器
*
* <p>生成 6-20 位微信号：字母开头，可含字母、数字与下划线，
* 如 {@code wx_ab8k3m9}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"wechat", "wechat-id", "wx"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class WechatMockString implements MockString {

    /**
    * 字母池
    */
    private static final char[] LETTERS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    /**
    * 字符池（字母 + 数字 + 下划线）
    */
    private static final char[] CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_".toCharArray();
    /**
    * 默认长度下界
    */
    private static final int DEFAULT_MIN = 6;
    /**
    * 默认长度上界
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final int DEFAULT_MAX = 20;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int length = environment.length();
        if (length < DEFAULT_MIN || length > DEFAULT_MAX) {
            length = environment.nextInt(DEFAULT_MIN, DEFAULT_MAX + 1);
        }
        StringBuilder builder = new StringBuilder(length);
        builder.append(LETTERS[environment.nextInt(LETTERS.length)]);
        for (int i = 1; i < length; i++) {
            builder.append(CHARS[environment.nextInt(CHARS.length)]);
        }
        return builder.toString();
    }
}
