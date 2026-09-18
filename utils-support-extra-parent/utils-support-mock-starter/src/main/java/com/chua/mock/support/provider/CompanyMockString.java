package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 公司名称 Mock 生成器
*
* <p>由城市前缀、核心词、行业词与公司类型后缀组合生成，
* 如「深圳市星辰科技有限公司」。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("company")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class CompanyMockString implements MockString {

    /**
    * 城市前缀池
    */
    private static final String[] CITIES = {
            "北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "武汉", "长沙", "厦门"
    };
    /**
    * 核心词池
    */
    private static final String[] CORES = {
            "华科", "天辰", "星河", "云帆", "瑞丰", "金桥", "创新", "联动", "方舟", "蓝海",
            "智联", "恒信", "卓越", "世纪", "东方", "未来", "环球", "嘉禾", "远航", "星辰"
    };
    /**
    * 行业词池
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final String[] INDUSTRIES = {
            "科技", "网络", "信息", "智能", "数据", "软件", "电子", "文化", "传媒", "实业"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(CITIES) + environment.randomOf(CORES)
                + environment.randomOf(INDUSTRIES) + "有限公司";
    }
}
