package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* ipv6 Mock 生成器
*
* <p>生成 8 组十六进制段的完整 IPv6 地址，如
* {@code 2001:0db8:85a3:0000:0000:8a2e:0370:7334}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"ipv6", "ip-v6"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class Ipv6MockString implements MockString {

    /**
    * 十六进制字符池
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(39);
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                builder.append(':');
            }
            for (int j = 0; j < 4; j++) {
                builder.append(HEX[environment.nextInt(16)]);
            }
        }
        return builder.toString();
    }
}