package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * MAC 地址 Mock 生成器
 *
 * <p>生成单播（首字节偶数、非全零）的 6 组十六进制 MAC 地址，
 * 如 {@code 3c:a8:12:6f:9b:04}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"mac", "mac-address"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class MacMockString implements MockString {

    /**
     * 十六进制字符池
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                builder.append(':');
            }
            if (i == 0) {
                // 首字节取偶数（2-254），保证单播且非全零
                int first = 2 + environment.nextInt(126) * 2;
                builder.append(HEX[first / 16]).append(HEX[first % 16]);
            } else {
                builder.append(HEX[environment.nextInt(16)]).append(HEX[environment.nextInt(16)]);
            }
        }
        return builder.toString();
    }
}