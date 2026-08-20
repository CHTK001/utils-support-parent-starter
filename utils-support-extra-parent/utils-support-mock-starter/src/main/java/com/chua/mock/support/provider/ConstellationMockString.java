package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 星座 Mock 生成器
 *
 * <p>随机返回十二星座之一，如「双子座」。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("constellation")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class ConstellationMockString implements MockString {

    /**
     * 十二星座池
     */
    private static final String[] CONSTELLATIONS = {
            "白羊座", "金牛座", "双子座", "巨蟹座", "狮子座", "处女座",
            "天秤座", "天蝎座", "射手座", "摩羯座", "水瓶座", "双鱼座"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(CONSTELLATIONS);
    }
}