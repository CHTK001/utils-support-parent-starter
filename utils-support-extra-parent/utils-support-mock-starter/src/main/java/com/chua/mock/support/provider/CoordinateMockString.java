package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
* 经纬度 Mock 生成器
*
* <p>在中国大陆范围（经度 73°-135°，纬度 18°-54°）内随机生成
* {@code 经度,纬度} 格式的坐标字符串，保留 6 位小数，如
* {@code 116.407394,39.904211}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"coordinate", "latlng", "lnglat", "gps"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class CoordinateMockString implements MockString {

    /**
    * 经度下界（包含）
     */
    private static final double LNG_MIN = 73.0;
    /**
    * 经度上界（不包含）
     */
    private static final double LNG_MAX = 136.0;
    /**
    * 纬度下界（包含）
     */
    private static final double LAT_MIN = 18.0;
    /**
    * 纬度上界（不包含）
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final double LAT_MAX = 54.0;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        double lng = LNG_MIN + environment.random().nextDouble() * (LNG_MAX - LNG_MIN);
        double lat = LAT_MIN + environment.random().nextDouble() * (LAT_MAX - LAT_MIN);
        return String.format(Locale.ROOT, "%.6f,%.6f", lng, lat);
    }
}