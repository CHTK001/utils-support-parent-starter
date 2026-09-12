package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 发票号码 Mock 生成器
*
* <p>生成 8 位增值税发票号码，如 {@code 32458761}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"invoice", "invoice-no", "invoice-number"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class InvoiceMockString implements MockString {

    /**
    * 发票号码长度
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final int LENGTH = 8;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.toString();
    }
}