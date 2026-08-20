package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * IPv4 Mock 生成器
 *
 * <p>随机生成形如 {@code 192.168.1.100} 的 IPv4 地址，
 * 各段取值范围 [0, 255] 且首个段取值 [1, 223] 以避开保留地址。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ip")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class IpMockString implements MockString {

    /**
     * 首个网段上界（不含，223 避免广播与保留地址）
     */
    private static final int FIRST_OCTET_MAX = 223;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int a = environment.nextInt(1, FIRST_OCTET_MAX);
        int b = environment.nextInt(256);
        int c = environment.nextInt(256);
        int d = environment.nextInt(256);
        return a + "." + b + "." + c + "." + d;
    }
}