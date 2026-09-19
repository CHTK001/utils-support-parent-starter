package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 统一社会信用代码 Mock 生成器
 *
 * <p>按 GB 32100-2015 生成 18 位统一社会信用代码：
 * 登记管理部门 + 机构类别 + 6 位行政区划 + 9 位主体标识码（含组织机构代码校验位）+ 1 位校验码，
 * 校验码依据 mod 31 算法计算，保证格式合法。
 * 仅用于测试数据填充，不代表真实机构。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"uscc", "credit-code", "social-credit-code"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class UsccMockString implements MockString {

    /**
     * 登记管理部门代码（1-9，1 为机构编制，5 为民政，9 为工商等）
     */
    private static final Integer[] ADMIN_DEPARTMENTS = {1, 2, 3, 4, 5, 9};
    /**
     * 机构类别代码（1 企业法人、2 非法人企业、3 个体工商户、4 农民专业合作社）
     */
    private static final Integer[] INSTITUTION_TYPES = {1, 2, 3, 4};
    /**
     * mod 31 字符集（排除易混淆的 I、O、S、V、Z）
     */
    private static final String MOD31_CHARS = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    /**
     * 组织机构代码校验权重（8 位主体码，MOD 11-2）
     */
    private static final int[] ORG_WEIGHTS = {3, 7, 9, 10, 5, 8, 4, 2};
    /**
     * 统一社会信用代码校验权重（17 位，mod 31）
     */
    private static final int[] USCC_WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
    /**
     * 行政区划代码池（与 {@link CnIdCardUtils} 保持一致的真实区划）
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final String[] ADMIN_CODES = {
            "110000", "120000", "310000", "500000", "440100", "440300", "330100",
            "330200", "510100", "320100", "320500", "370100", "370200", "420100",
            "210100", "350100", "350200", "430100", "410100", "610100", "510800"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(18);
        builder.append(environment.randomOf(ADMIN_DEPARTMENTS));
        builder.append(environment.randomOf(INSTITUTION_TYPES));
        builder.append(environment.randomOf(ADMIN_CODES));
        StringBuilder orgBody = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            orgBody.append(MOD31_CHARS.charAt(environment.nextInt(MOD31_CHARS.length())));
        }
        builder.append(orgBody);
        builder.append(orgCodeCheck(orgBody));
        builder.append(usccCheck(builder));
        return builder.toString();
    }

    /**
     * 计算组织机构代码校验位（MOD 11-2）。
     *
     * @param body 8 位主体码
     * @return 校验位字符（0-9 或 X）
     */
    private static char orgCodeCheck(StringBuilder body) {
        int sum = 0;
        for (int i = 0; i < ORG_WEIGHTS.length; i++) {
            sum += MOD31_CHARS.indexOf(body.charAt(i)) * ORG_WEIGHTS[i];
        }
        int mod = sum % 11;
        int check = (11 - mod) % 11;
        return check == 10 ? 'X' : (char) ('0' + check);
    }

    /**
     * 计算统一社会信用代码校验码（mod 31）。
     *
     * @param body 前 17 位
     * @return 校验码字符
     */
    private static char usccCheck(StringBuilder body) {
        int sum = 0;
        for (int i = 0; i < USCC_WEIGHTS.length; i++) {
            sum += MOD31_CHARS.indexOf(body.charAt(i)) * USCC_WEIGHTS[i];
        }
        return MOD31_CHARS.charAt((31 - sum % 31) % 31);
    }
}
