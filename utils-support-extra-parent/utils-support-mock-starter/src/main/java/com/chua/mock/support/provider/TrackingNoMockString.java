package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 快递单号 Mock 生成器
*
* <p>生成带快递公司前缀或不带前缀的运单号，
* 如 {@code SF1234567890123}、{@code 7536482910452}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"tracking-no", "express-no", "waybill-no", "courier-no"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class TrackingNoMockString implements MockString {

    /**
    * 快递公司前缀池（空串表示无前缀）
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final String[] PREFIXES = {"SF", "YT", "ZT", "YD", "JD", "STO", "YTO", ""};

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        String prefix = environment.randomOf(PREFIXES);
        int length = environment.nextInt(12, 16);
        StringBuilder builder = new StringBuilder(prefix.length() + length);
        builder.append(prefix);
        for (int i = 0; i < length; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.toString();
    }
}