package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
* 交易流水号 Mock 生成器
*
* <p>生成形如 {@code SN202608201516450001234567} 的交易流水号：
* 前缀 SN + 时间戳（yyyymmddhhmmss）+ 9 位随机数字。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"serial-no", "serial", "transaction-no", "flow-no"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class SerialNoMockString implements MockString {

    /**
    * 时间戳格式
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(21);
        builder.append("SN").append(LocalDateTime.now().format(TIMESTAMP));
        for (int i = 0; i < 9; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.toString();
    }
}