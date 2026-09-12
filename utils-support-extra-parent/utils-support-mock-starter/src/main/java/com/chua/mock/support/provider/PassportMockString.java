package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 护照号 Mock 生成器
*
* <p>生成 9 位护照号：字母 E/G（中国因私/因公普通护照）+ 8 位数字，
* 如 {@code E12345678}。仅用于测试数据填充，不代表真实护照信息。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"passport", "passport-no", "passport-number"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class PassportMockString implements MockString {

    /**
    * 护照前缀字母池
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final char[] PREFIXES = {'E', 'G', 'D', 'P'};

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(9);
        builder.append(PREFIXES[environment.nextInt(PREFIXES.length)]);
        for (int i = 0; i < 8; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.toString();
    }
}