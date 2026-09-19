package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * VIN 车架号 Mock 生成器
 *
 * <p>按 ISO 3779 生成 17 位车辆识别代号：3 位 WMI + 6 位 VDS + 1 位校验位
 * （MOD 11，第 9 位）+ 8 位 VIS。字符集排除易混淆的 I、O、Q，
 * 校验位计算保证格式合法，仅用于测试数据填充。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"vin", "car-vin"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class VinMockString implements MockString {

    /**
     * VIN 字符集（排除 I、O、Q）
     */
    private static final String VIN_CHARS = "0123456789ABCDEFGHJKLMNPRSTUVWXYZ";
    /**
     * VIN 权重（第 9 位权重为 0）
     */
    private static final int[] WEIGHTS = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};
    /**
     * VIN 长度
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final int LENGTH = 17;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        char[] vin = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            if (i == 8) {
                continue;
            }
            vin[i] = VIN_CHARS.charAt(environment.nextInt(VIN_CHARS.length()));
        }
        vin[8] = checkDigit(vin);
        return new String(vin);
    }

    /**
     * 计算 VIN 第 9 位校验位。
     *
     * @param vin 含占位第 9 位的完整数组
     * @return 校验位字符（0-9 或 X）
     */
    private static char checkDigit(char[] vin) {
        int sum = 0;
        for (int i = 0; i < LENGTH; i++) {
            if (i == 8) {
                continue;
            }
            sum += transliterate(vin[i]) * WEIGHTS[i];
        }
        int mod = sum % 11;
        return mod == 10 ? 'X' : (char) ('0' + mod);
    }

    /**
     * 将 VIN 字符转换为数字值（MOD 11 替换表）。
     *
     * @param ch VIN 字符
     * @return 数字值
     */
    private static int transliterate(char ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        return switch (ch) {
            case 'A' -> 1;
            case 'B' -> 2;
            case 'C' -> 3;
            case 'D' -> 4;
            case 'E' -> 5;
            case 'F' -> 6;
            case 'G' -> 7;
            case 'H' -> 8;
            case 'J' -> 1;
            case 'K' -> 2;
            case 'L' -> 3;
            case 'M' -> 4;
            case 'N' -> 5;
            case 'P' -> 7;
            case 'R' -> 9;
            case 'S' -> 2;
            case 'T' -> 3;
            case 'U' -> 4;
            case 'V' -> 5;
            case 'W' -> 6;
            case 'X' -> 7;
            case 'Y' -> 8;
            case 'Z' -> 9;
            default -> 0;
        };
    }
}
