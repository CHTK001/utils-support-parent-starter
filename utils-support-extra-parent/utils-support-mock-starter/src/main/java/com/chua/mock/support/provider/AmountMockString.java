package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * 金额 Mock 生成器
 *
 * <p>生成人民币金额字符串（两位小数），如 {@code ¥1234.56}，
 * 金额范围 [1.00, 999999.99]。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"amount", "money", "price"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class AmountMockString implements MockString {

    /**
     * 最小金额（分）
     */
    private static final long MIN_CENTS = 100L;
    /**
     * 最大金额（分，不含）
     */
    private static final long MAX_CENTS = 100_000_000L;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        long cents = environment.nextLong(MIN_CENTS, MAX_CENTS);
        BigDecimal amount = BigDecimal.valueOf(cents, 2);
        return "¥" + amount.setScale(2);
    }
}