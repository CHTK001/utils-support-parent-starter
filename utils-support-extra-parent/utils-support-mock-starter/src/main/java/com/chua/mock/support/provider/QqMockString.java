package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* QQ 号 Mock 生成器
*
* <p>生成 5-11 位 QQ 号（首位非零），如 {@code 123456789}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"qq", "qq-no", "qq-number"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class QqMockString implements MockString {

    /**
    * 长度下界（包含）
    */
    private static final int LENGTH_MIN = 5;
    /**
    * 长度上界（包含）
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final int LENGTH_MAX = 11;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int length = environment.nextInt(LENGTH_MIN, LENGTH_MAX + 1);
        StringBuilder builder = new StringBuilder(length);
        builder.append(environment.nextInt(1, 10));
        for (int i = 1; i < length; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.toString();
    }
}
