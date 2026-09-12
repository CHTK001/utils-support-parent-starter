package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 邮箱 Mock 生成器
*
* <p>由随机用户名与常用邮箱域名池组合生成，如 {@code a8f3k2@163.com}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("email")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class EmailMockString implements MockString {

    /**
    * 用户名随机字符池（小写字母 + 数字）
     */
    private static final char[] USERNAME_CHARS =
            "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    /**
    * 常用邮箱域名池
     */
    private static final String[] DOMAINS = {
            "qq.com", "163.com", "126.com", "sina.com", "foxmail.com",
            "gmail.com", "outlook.com", "yahoo.com", "hotmail.com", "icloud.com"
    };
    /**
    * 用户名长度区间 [5, 12]
     */
    private static final int USERNAME_MIN = 5;
    /**
    * 用户名长度上界
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final int USERNAME_MAX = 13;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int length = environment.nextInt(USERNAME_MIN, USERNAME_MAX);
        StringBuilder builder = new StringBuilder(length + DOMAINS[0].length());
        for (int i = 0; i < length; i++) {
            builder.append(USERNAME_CHARS[environment.nextInt(USERNAME_CHARS.length)]);
        }
        return builder.append('@').append(environment.randomOf(DOMAINS)).toString();
    }
}
