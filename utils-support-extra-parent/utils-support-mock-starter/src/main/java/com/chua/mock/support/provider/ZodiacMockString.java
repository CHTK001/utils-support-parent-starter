package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 生肖 Mock 生成器
*
* <p>随机返回十二生肖之一，如「龙」。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("zodiac")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class ZodiacMockString implements MockString {

    /**
    * 十二生肖池
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final String[] ZODIACS = {"鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"};

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(ZODIACS);
    }
}
