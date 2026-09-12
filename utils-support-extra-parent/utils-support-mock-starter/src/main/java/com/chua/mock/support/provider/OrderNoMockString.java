package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
* 订单号 Mock 生成器
*
* <p>生成形如 {@code O2026082015164501234567} 的订单号：
* 前缀 O + 时间戳（yyyymmddhhmmsssss）+ 4 位随机数字。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"order-no", "order-id", "order"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class OrderNoMockString implements MockString {

    /**
    * 时间戳格式
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(24);
        builder.append('O').append(LocalDateTime.now().format(TIMESTAMP));
        for (int i = 0; i < 4; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.toString();
    }
}