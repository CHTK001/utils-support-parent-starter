package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;

import javax.annotation.Nonnull;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 中国身份证号码工具类
 *
 * <p>按 GB 11643-1999 提供 18 位身份证号的区划代码、出生日期与校验码生成逻辑，
 * 供 {@link IdCardMockString}、{@link DriverLicenseMockString} 等生成器复用，
 * 保证「单一数据源」。生成的号码仅用于测试数据填充。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class CnIdCardUtils {

    /**
     * 出生日期格式
     */
    private static final DateTimeFormatter BIRTHDAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 行政区划代码池（前两位为省，后四位为地市/区县）
     */
    static final String[] REGION_CODES = {
            "110101", "110102", "110105", "110108", "310101", "310104", "310115",
            "440101", "440103", "440105", "440106", "440107", "330101", "330102",
            "330106", "510101", "510102", "510107", "320101", "320102", "320105",
            "370101", "370102", "370104", "420101", "420102", "420105", "210101",
            "210102", "350101", "350102", "430101", "430102", "410101", "410102",
            "500101", "500103", "500105"
    };
    /**
     * 权重系数（17 位加权因子）
     */
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    /**
     * 校验码映射表（mod 11）
     */
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    /**
     * 私有构造方法，防止实例化。
     */
    private CnIdCardUtils() {
    }

    /**
     * 生成 18 位身份证号。
     *
     * @param environment  Mock 环境
     * @param birthdayMin  出生日期范围下界（包含）
     * @param birthdayMax  出生日期范围上界（包含）
     * @return 18 位身份证号
     */
    @Nonnull
    static String generate(@Nonnull MockEnvironment environment, @Nonnull String birthdayMin, @Nonnull String birthdayMax) {
        StringBuilder builder = new StringBuilder(18);
        builder.append(environment.randomOf(REGION_CODES));
        builder.append(randomBirthday(environment, birthdayMin, birthdayMax));
        for (int i = 0; i < 3; i++) {
            builder.append(environment.nextInt(10));
        }
        builder.append(checkCode(builder));
        return builder.toString();
    }

    /**
      * 生成随机的出生日期字符串（yyyymmdd）。
     *
     * @param environment Mock 环境
     * @param min         范围下界（包含）
     * @param max         范围上界（包含）
     * @return 出生日期字符串
     */
    private static String randomBirthday(MockEnvironment environment, String min, String max) {
        long minEpoch = LocalDate.parse(min).toEpochDay();
        long maxEpoch = LocalDate.parse(max).toEpochDay();
        LocalDate birthday = LocalDate.ofEpochDay(environment.nextLong(minEpoch, maxEpoch + 1));
        return birthday.format(BIRTHDAY_FORMATTER);
    }

    /**
     * 根据前 17 位计算身份证校验码。
     *
     * @param body 前 17 位数字串
     * @return 校验码字符（0-9 或 X）
     */
    static char checkCode(StringBuilder body) {
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += (body.charAt(i) - '0') * WEIGHTS[i];
        }
        return CHECK_CODES[sum % 11];
    }
}