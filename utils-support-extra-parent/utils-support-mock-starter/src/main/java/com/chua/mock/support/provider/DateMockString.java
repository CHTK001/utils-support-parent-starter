package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 日期 Mock 生成器
 *
 * <p>在 1970-01-01 至当前日期之间随机生成 {@code yyyy-MM-dd} 格式的日期字符串。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("date")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class DateMockString implements MockString {

    /**
     * 日期格式
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        long today = LocalDate.now().toEpochDay();
        long epochDay = environment.nextLong(0, today + 1);
        return LocalDate.ofEpochDay(epochDay).format(FORMATTER);
    }
}
