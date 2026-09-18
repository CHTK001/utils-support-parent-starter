package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 组织机构代码 Mock 生成器
*
* <p>按 GB 11714 生成 9 位组织机构代码（8 位主体码 + 1 位校验位），
* 展示格式 {@code XXXXXXXX-Y}，校验位依据 MOD 11-2 算法计算。
* 仅用于测试数据填充，不代表真实机构。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"org-code", "organization-code", "orgcode"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class OrgCodeMockString implements MockString {

    /**
    * 主体码字符集
    */
    private static final String CHARS = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    /**
    * 校验权重（8 位主体码）
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final int[] WEIGHTS = {3, 7, 9, 10, 5, 8, 4, 2};

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder body = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            body.append(CHARS.charAt(environment.nextInt(CHARS.length())));
        }
        return body.toString() + "-" + checkCode(body);
    }

    /**
    * 计算组织机构代码校验位（MOD 11-2）。
    *
    * @param body 8 位主体码
    * @return 校验位字符（0-9 或 X）
    */
    private static char checkCode(StringBuilder body) {
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += CHARS.indexOf(body.charAt(i)) * WEIGHTS[i];
        }
        int check = (11 - sum % 11) % 11;
        return check == 10 ? 'X' : (char) ('0' + check);
    }
}
