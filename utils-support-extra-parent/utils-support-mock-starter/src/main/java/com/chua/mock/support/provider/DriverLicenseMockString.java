package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 驾驶证号 Mock 生成器
 *
 * <p>驾驶证号即 18 位身份证号（出生日期限定为已满 18 周岁），
 * 复用 {@link CnIdCardUtils} 生成，校验码合法。
 * 仅用于测试数据填充，不代表真实人员的驾驶资质信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"driver-license", "driver-license-no"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class DriverLicenseMockString implements MockString {

    /**
     * 出生日期范围下界（包含）
     */
    private static final String BIRTHDAY_MIN = "1960-01-01";
    /**
     * 出生日期范围上界（包含，保证已满 18 周岁）
     */
    private static final String BIRTHDAY_MAX = "2008-08-20";

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return CnIdCardUtils.generate(environment, BIRTHDAY_MIN, BIRTHDAY_MAX);
    }
}