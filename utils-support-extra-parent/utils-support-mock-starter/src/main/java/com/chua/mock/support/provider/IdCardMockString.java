package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 身份证号 Mock 生成器
*
* <p>按 GB 11643-1999 生成 18 位身份证号：
* 6 位行政区划代码 + 8 位出生日期（yyyymmdd）+ 3 位顺序码 + 1 位校验码，
* 校验码依据 mod 11-2 算法计算，保证格式合法。
* 仅用于测试数据填充，不代表真实人员的身份信息。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"idcard", "id-card", "identity-card", "sfz"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class IdCardMockString implements MockString {

    /**
    * 出生日期范围下界（包含）
    */
    private static final String BIRTHDAY_MIN = "1960-01-01";
    /**
    * 出生日期范围上界（包含）
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final String BIRTHDAY_MAX = "2005-12-31";

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return CnIdCardUtils.generate(environment, BIRTHDAY_MIN, BIRTHDAY_MAX);
    }
}
