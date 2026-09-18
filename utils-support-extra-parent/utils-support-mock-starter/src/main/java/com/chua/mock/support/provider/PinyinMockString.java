package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 姓名拼音 Mock 生成器
*
* <p>由姓氏拼音与名字拼音池组合生成，首字母大写的无空格拼音，
* 如「zhangwei」「lijingwen」。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"pinyin", "name-pinyin"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class PinyinMockString implements MockString {

    /**
    * 姓氏拼音池
    */
    private static final String[] SURNAMES = {
            "Zhang", "Li", "Wang", "Zhao", "Liu", "Chen", "Yang", "Huang", "Zhou", "Wu",
            "Xu", "Sun", "Ma", "Zhu", "Hu", "Guo", "He", "Gao", "Lin", "Luo", "Zheng", "Liang"
    };
    /**
    * 名字拼音池
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final String[] GIVEN_NAMES = {
            "Wei", "Fang", "Na", "Min", "Jing", "Li", "Qiang", "Lei", "Jun", "Yang",
            "Yong", "Yan", "Jie", "Juan", "Tao", "Ming", "Chao", "Xiu", "Xia", "Ping",
            "Gang", "Gui", "Ying", "Hua", "Jin", "Long", "Yu", "Shan", "Zi", "Xuan"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(12);
        builder.append(environment.randomOf(SURNAMES));
        int givenLength = environment.nextInt(1, 3);
        for (int i = 0; i < givenLength; i++) {
            builder.append(environment.randomOf(GIVEN_NAMES));
        }
        return builder.toString();
    }
}
