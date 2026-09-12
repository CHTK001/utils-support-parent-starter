package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 昵称 Mock 生成器
*
* <p>由修饰词 + 意象词 + 可选数字后缀组合生成，如「快乐的奶茶」「小鱼儿 2024」。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("nickname")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class NicknameMockString implements MockString {

    /**
    * 修饰词池
     */
    private static final String[] PREFIXES = {
            "快乐的", "可爱的", "爱笑的", "阳光的", "帅气的", "甜甜的", "酷酷的", "温柔的",
            "软萌的", "元气", "机智的", "浪漫的", "追风的", "安静的", "傲娇的", "暖心"
    };
    /**
    * 意象词池
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final String[] IMAGES = {
            "猫咪", "小鱼", "星星", "月亮", "奶茶", "柠檬", "布丁", "小鹿",
            "云朵", "泡泡", "小熊", "海豚", "企鹅", "兔子", "狐狸", "椰子"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(12);
        builder.append(environment.randomOf(PREFIXES)).append(environment.randomOf(IMAGES));
        if (environment.nextInt(2) == 0) {
            builder.append(' ').append(environment.nextInt(10, 100));
        }
        return builder.toString();
    }
}