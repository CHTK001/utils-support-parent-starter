package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 邮编 Mock 生成器
 *
 * <p>生成中国大陆 6 位邮政编码，首位数取自 [1, 9] 以避开无效地区段，
 * 如「518000」。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"postcode", "zip", "zipcode"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class PostcodeMockString implements MockString {

    /**
     * 邮编固定长度
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final int LENGTH = 6;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(LENGTH);
        builder.append(environment.nextInt(1, 10));
        for (int i = 1; i < LENGTH; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.toString();
    }
}