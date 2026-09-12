package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 中文姓名 Mock 生成器
 *
 * <p>由百家姓姓氏与常用名用字池组合生成 2-4 字中文姓名，
 * 如「张伟」「李静雯」。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"name", "cn-name", "chinese-name"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class NameMockString implements MockString {

    /**
     * 百家姓姓氏池
     */
    private static final String SURNAMES =
            "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜" +
            "戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳酆鲍史唐" +
            "费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄" +
            "和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁" +
            "杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍" +
            "虞万支柯昝管卢莫经房裘缪干解应宗丁宣贲邓郁单杭洪包诸左石崔吉钮龚";
    /**
     * 常用名用字池
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final String GIVEN_NAMES =
            "伟芳娜敏静丽强磊军洋勇艳杰娟涛明超秀霞平刚桂英华金龙玉山" +
            "雪思宇飞浩楠雪晨阳欢欣怡雨佳琪语嫣欣怡子涵梓萱浩然嘉懿睿泽";

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(4);
        builder.append(SURNAMES.charAt(environment.nextInt(SURNAMES.length())));
        int givenLength = environment.nextInt(1, 3);
        for (int i = 0; i < givenLength; i++) {
            builder.append(GIVEN_NAMES.charAt(environment.nextInt(GIVEN_NAMES.length())));
        }
        return builder.toString();
    }
}
