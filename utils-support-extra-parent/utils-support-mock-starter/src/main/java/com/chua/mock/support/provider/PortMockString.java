package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 端口号 Mock 生成器
*
* <p>生成 [1024, 65535] 范围内的动态端口号，避开 0-1023 知名端口。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"port", "tcp-port"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class PortMockString implements MockString {

    /**
    * 端口下界（包含）
     */
    private static final int PORT_MIN = 1024;
    /**
    * 端口上界（包含）
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final int PORT_MAX = 65535;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return String.valueOf(environment.nextInt(PORT_MIN, PORT_MAX + 1));
    }
}