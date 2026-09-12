package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 股票代码 Mock 生成器
 *
 * <p>生成 A 股 6 位股票代码，沪市（60xxxx）与深市（00xxxx、30xxxx）随机，
 * 如 {@code 600519}、{@code 300750}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"stock-code", "stock"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class StockCodeMockString implements MockString {

    /**
     * A 股代码段前缀池
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final String[] PREFIXES = {"600", "601", "603", "605", "000", "001", "002", "003", "300", "301"};

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(6);
        builder.append(environment.randomOf(PREFIXES));
        for (int i = 0; i < 3; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.toString();
    }
}