// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 日期时间 Mock 生成器
 *
 * <p>在 1970-01-01 至当前日期之间随机生成 {@code yyyy-MM-dd HH:mm:ss} 格式的日期时间字符串。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"datetime", "timestamp"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class DateTimeMockString implements MockString {

    /**
     * 日期时间格式
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Nonnull
    /**
     * get String
     * @param environment 参数
     * @return 返回值
    */
    public String getString(@Nonnull MockEnvironment environment) {
        long today = LocalDate.now().toEpochDay();
        LocalDate date = LocalDate.ofEpochDay(environment.nextLong(0, today + 1));
        LocalTime time = LocalTime.of(
                environment.nextInt(24),
                environment.nextInt(60),
                environment.nextInt(60));
        return LocalDateTime.of(date, time).format(FORMATTER);
    }
}