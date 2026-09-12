package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 省份 Mock 生成器
*
* <p>从 34 个省级行政区中随机返回一个名称（含省、自治区、直辖市、特别行政区）。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("province")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class ProvinceMockString implements MockString {

    /**
    * 省级行政区池
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final String[] PROVINCES = {
            "北京市", "天津市", "上海市", "重庆市", "河北省", "山西省", "辽宁省", "吉林省",
            "黑龙江省", "江苏省", "浙江省", "安徽省", "福建省", "江西省", "山东省", "河南省",
            "湖北省", "湖南省", "广东省", "海南省", "四川省", "贵州省", "云南省", "陕西省",
            "甘肃省", "青海省", "内蒙古自治区", "广西壮族自治区", "西藏自治区", "宁夏回族自治区",
            "新疆维吾尔自治区", "台湾省", "香港特别行政区", "澳门特别行政区"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(PROVINCES);
    }
}