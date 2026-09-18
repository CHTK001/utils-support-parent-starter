package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* CVV Mock 生成器
*
* <p>生成银行卡安全码 CVV（3 位数字），如 {@code 835}。
* 仅用于测试数据填充，不代表真实卡片信息。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"cvv", "cvv2", "security-code"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class CvvMockString implements MockString {

    /**
    * CVV 长度
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final int LENGTH = 3;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.toString();
    }
}
