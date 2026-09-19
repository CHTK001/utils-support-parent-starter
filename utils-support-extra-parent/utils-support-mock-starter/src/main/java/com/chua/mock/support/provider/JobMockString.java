package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 职业 Mock 生成器
 *
 * <p>从常见职业池中随机返回一个职业名称，如「软件工程师」。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"job", "occupation", "career"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class JobMockString implements MockString {

    /**
     * 职业池
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final String[] JOBS = {
            "医生", "教师", "工程师", "程序员", "设计师", "会计", "律师", "厨师", "司机",
            "警察", "护士", "记者", "编辑", "演员", "歌手", "运动员", "摄影师", "画家",
            "作家", "科学家", "公务员", "经理", "销售", "客服", "快递员", "外卖员",
            "建筑工人", "木匠", "电工", "园艺师", "兽医", "飞行员", "船长", "导游"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(JOBS);
    }
}
