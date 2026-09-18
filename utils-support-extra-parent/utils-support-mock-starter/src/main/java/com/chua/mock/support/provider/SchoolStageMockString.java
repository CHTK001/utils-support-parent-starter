package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 学段 Mock 生成器
*
* <p>随机返回我国教育学段之一：幼儿园、小学、初中、高中、中专、大专、本科、硕士、博士。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"school-stage", "stage", "education-stage", "education-level"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class SchoolStageMockString implements MockString {

    /**
    * 学段池
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final String[] STAGES = {
            "幼儿园", "小学", "初中", "高中", "中专", "大专", "本科", "硕士", "博士"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(STAGES);
    }
}
