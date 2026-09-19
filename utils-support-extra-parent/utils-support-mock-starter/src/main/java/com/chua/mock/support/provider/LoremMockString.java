package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * Lorem Ipsum 假文 Mock 生成器
 *
 * <p>按环境指定的单词数量生成 lorem ipsum 英文假文，
 * 默认 10 个单词，如 {@code Lorem ipsum dolor sit amet}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"lorem", "lorem-ipsum", "lipsum"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class LoremMockString implements MockString {

    /**
     * lorem ipsum 单词池
     */
    private static final String[] WORDS = {
            "lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit",
            "sed", "do", "eiusmod", "tempor", "incididunt", "ut", "labore", "et", "dolore",
            "magna", "aliqua", "enim", "ad", "minim", "veniam", "quis", "nostrud",
            "exercitation", "ullamco", "laboris", "nisi", "aliquip", "ex", "ea", "commodo",
            "consequat", "duis", "aute", "irure", "in", "reprehenderit", "voluptate", "velit"
    };
    /**
     * 默认单词数量
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final int DEFAULT_WORDS = 10;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int count = environment.length();
        if (count < 1 || count > 50) {
            count = DEFAULT_WORDS;
        }
        StringBuilder builder = new StringBuilder(count * 8);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(environment.randomOf(WORDS));
        }
        return builder.toString();
    }
}
