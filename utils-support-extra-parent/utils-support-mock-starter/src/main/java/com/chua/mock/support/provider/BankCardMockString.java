package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 银行卡号 Mock 生成器
*
* <p>以常见银行卡 BIN（发卡行标识）前缀生成 16 或 19 位卡号，
* 末位补 Luhn 校验位，保证生成的卡号通过基础 Luhn 校验，
* 仅用于测试数据填充，不代表真实可用卡号。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"bankcard", "bank-card", "card-no", "cardno"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class BankCardMockString implements MockString {

    /**
    * 常见银行卡 BIN 前缀池
     */
    private static final String[] BINS = {
            "622202", "621226", "622848", "622700", "622260", "622155",
            "622588", "622188", "621700", "622280", "622161", "621661",
            "622211", "621558", "622200", "621280", "622622", "622168"
    };
    /**
    * 16 位卡号总长度
     */
    private static final int LENGTH_16 = 16;
    /**
    * 19 位卡号总长度
    * @param environment 环境
    * @return 获取字符串的结果
     */
    private static final int LENGTH_19 = 19;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int total = environment.nextInt(2) == 0 ? LENGTH_16 : LENGTH_19;
        StringBuilder builder = new StringBuilder(total);
        String bin = environment.randomOf(BINS);
        builder.append(bin);
        int bodyLength = total - bin.length() - 1;
        for (int i = 0; i < bodyLength; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.append(luhnCheckDigit(builder)).toString();
    }

    /**
    * 根据现有数字位计算 Luhn 校验位。
    *
    * @param digits 不含校验位的数字串
    * @return 校验位字符
     */
    private static char luhnCheckDigit(StringBuilder digits) {
        int sum = 0;
        boolean alternate = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n <<= 1;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }
}