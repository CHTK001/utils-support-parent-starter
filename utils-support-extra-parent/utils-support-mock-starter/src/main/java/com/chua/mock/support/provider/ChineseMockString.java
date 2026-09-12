package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 中文汉字 Mock 生成器
*
* <p>在 CJK 统一表意文字区（U+4E00 ~ U+9FA5）内按环境指定长度随机抽取汉字。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"chinese", "cn", "hanzi"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class ChineseMockString implements MockString {

    /**
    * CJK 统一表意文字区起始码点
     */
    private static final int CJK_START = 0x4E00;
    /**
    * CJK 统一表意文字区结束码点（不包含）
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final int CJK_END = 0x9FA5;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int length = environment.length();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append((char) environment.nextInt(CJK_START, CJK_END));
        }
        return builder.toString();
    }
}