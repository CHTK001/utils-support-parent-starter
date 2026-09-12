package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 性别 Mock 生成器
*
* <p>随机返回「男」或「女」。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("gender")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class GenderMockString implements MockString {

    /**
    * 性别池
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final String[] GENDERS = {"男", "女"};

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(GENDERS);
    }
}