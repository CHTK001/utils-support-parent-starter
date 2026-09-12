package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 学校名称 Mock 生成器
*
* <p>随机选取学段后生成对应学校名称：大学从真实高校池选取，
* 其余学段由城市名 + 学校类型后缀组合生成，
* 如「华中科技大学」「成都外国语学校」「武汉市育才小学」。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"school", "school-name"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class SchoolMockString implements MockString {

    /**
    * 知名高校池
     */
    private static final String[] UNIVERSITIES = {
            "清华大学", "北京大学", "浙江大学", "复旦大学", "上海交通大学", "南京大学",
            "武汉大学", "中山大学", "四川大学", "华中科技大学", "山东大学", "吉林大学",
            "厦门大学", "东南大学", "同济大学", "南开大学", "西安交通大学", "哈尔滨工业大学",
            "兰州大学", "中南大学", "电子科技大学", "重庆大学", "大连理工大学", "西北工业大学",
            "华南理工大学", "华东师范大学", "北京师范大学", "中国人民大学", "天津大学", "北京航空航天大学"
    };
    /**
    * 城市名池
     */
    private static final String[] CITIES = {
            "北京", "上海", "广州", "深圳", "杭州", "武汉", "成都", "南京", "西安", "长沙",
            "郑州", "青岛", "苏州", "厦门", "大连", "重庆", "天津", "合肥", "南昌", "福州"
    };
    /**
    * 高中类型后缀池
     */
    private static final String[] HIGH_SUFFIXES = {
            "第一中学", "第二中学", "实验中学", "高级中学", "外国语学校", "第七中学"
    };
    /**
    * 初中类型后缀池
     */
    private static final String[] MIDDLE_SUFFIXES = {
            "第一初级中学", "第二初级中学", "实验初级中学", "附属初级中学", "第三初级中学"
    };
    /**
    * 小学类型后缀池
     */
    private static final String[] PRIMARY_SUFFIXES = {
            "实验小学", "第一小学", "中心小学", "师范附属小学", "第二小学", "育才小学"
    };
    /**
    * 幼儿园类型后缀池
     */
    private static final String[] KINDERGARTEN_SUFFIXES = {
            "第一幼儿园", "中心幼儿园", "实验幼儿园", "第二幼儿园", "蓝天幼儿园"
    };
    /**
    * 大专院校类型后缀池
     */
    private static final String[] COLLEGE_SUFFIXES = {
            "职业技术学院", "职业学院", "科技职业学院", "信息职业学院", "交通职业学院"
    };
    /**
    * 学校可覆盖的学段池
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final String[] STAGES = {
            "幼儿园", "小学", "初中", "高中", "大专", "本科"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return switch (environment.randomOf(STAGES)) {
            case "本科" -> environment.randomOf(UNIVERSITIES);
            case "大专" -> environment.randomOf(CITIES) + environment.randomOf(COLLEGE_SUFFIXES);
            case "高中" -> environment.randomOf(CITIES) + environment.randomOf(HIGH_SUFFIXES);
            case "初中" -> environment.randomOf(CITIES) + environment.randomOf(MIDDLE_SUFFIXES);
            case "小学" -> environment.randomOf(CITIES) + environment.randomOf(PRIMARY_SUFFIXES);
            default -> environment.randomOf(CITIES) + environment.randomOf(KINDERGARTEN_SUFFIXES);
        };
    }
}