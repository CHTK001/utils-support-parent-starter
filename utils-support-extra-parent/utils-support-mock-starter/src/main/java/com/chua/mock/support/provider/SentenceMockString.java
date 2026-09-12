package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 句子 Mock 生成器
 *
 * <p>从常见中文句子池中随机返回一句话，如「今天天气不错，适合出门走走。」</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"sentence", "cn-sentence"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class SentenceMockString implements MockString {

    /**
     * 中文句子池
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final String[] SENTENCES = {
            "今天天气不错，适合出门走走。",
            "坚持就是胜利，努力终有回报。",
            "生活不止眼前的苟且，还有诗和远方。",
            "千里之行，始于足下。",
            "学而不思则罔，思而不学则殆。",
            "吃得苦中苦，方为人上人。",
            "失败是成功之母，不要轻易放弃。",
            "时间就是金钱，效率就是生命。",
            "一分耕耘，一分收获。",
            "团结就是力量，合作才能共赢。",
            "知识改变命运，学习成就未来。",
            "天下没有免费的午餐，付出才有回报。",
            "早起的鸟儿有虫吃。",
            "好记性不如烂笔头。",
            "有志者事竟成，破釜沉舟百二秦关终属楚。"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(SENTENCES);
    }
}