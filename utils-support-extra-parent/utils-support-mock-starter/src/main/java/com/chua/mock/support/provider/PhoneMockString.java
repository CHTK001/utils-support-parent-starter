package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 手机号 Mock 生成器
 *
 * <p>生成中国大陆 11 位手机号，格式为 {@code 1[3-9]xxxxxxxxx}，
 * 不保证号码真实可用，仅用于测试数据填充。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"phone", "mobile", "cellphone"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class PhoneMockString implements MockString {

    /**
     * 第二位号码取值区间（3-9）
     */
    private static final int SECOND_DIGIT_MIN = 3;
    /**
     * 第二位号码取值区间上界
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final int SECOND_DIGIT_MAX = 10;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(11);
        builder.append('1');
        builder.append(environment.nextInt(SECOND_DIGIT_MIN, SECOND_DIGIT_MAX));
        for (int i = 0; i < 9; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.toString();
    }
}
