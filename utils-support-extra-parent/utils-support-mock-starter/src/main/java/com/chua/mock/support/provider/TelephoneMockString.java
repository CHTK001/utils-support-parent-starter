package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 座机号 Mock 生成器
 *
 * <p>按中国主要城市区号生成座机号码，如「010-88886666」「0755-12345678」。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"telephone", "tel", "landline", "phone-landline"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class TelephoneMockString implements MockString {

    /**
     * 3 位区号城市（8 位号码）
     */
    private static final String[] CODE_3 = {"010", "021", "020", "022", "023"};
    /**
     * 4 位区号城市（7-8 位号码）
     */
    private static final String[] CODE_4 = {
            "0755", "0571", "028", "027", "025", "029", "0731", "0371", "024",
            "0532", "0411", "0592", "0351", "028", "0791", "0771", "0851", "0931",
            "0898", "0451", "0431", "0871", "0991", "0574", "0512", "0591"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        if (environment.nextInt(2) == 0) {
            String code = environment.randomOf(CODE_3);
            return code + "-" + randomDigits(environment, 8);
        }
        String code = environment.randomOf(CODE_4);
        int length = environment.nextInt(7, 9);
        return code + "-" + randomDigits(environment, length);
    }

    /**
     * 生成指定长度的随机数字串。
     *
     * @param environment Mock 环境
     * @param length      数字串长度
     * @return 数字串
     */
    private static String randomDigits(MockEnvironment environment, int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(environment.nextInt(10));
        }
        return builder.toString();
    }
}