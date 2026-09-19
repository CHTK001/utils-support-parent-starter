package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 城市 Mock 生成器
 *
 * <p>从中国主要城市池中随机返回一个城市名称，如「杭州」。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("city")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class CityMockString implements MockString {

    /**
     * 中国主要城市池
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final String[] CITIES = {
            "北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "武汉", "长沙", "厦门",
            "苏州", "无锡", "宁波", "温州", "福州", "济南", "青岛", "郑州", "西安", "重庆",
            "天津", "大连", "沈阳", "长春", "哈尔滨", "合肥", "南昌", "昆明", "贵阳", "南宁",
            "兰州", "西宁", "海口", "乌鲁木齐", "呼和浩特", "石家庄"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(CITIES);
    }
}
