package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
 * 车牌号 Mock 生成器
 *
 * <p>生成中国大陆民用汽车牌照号，格式为「省份简称 + 发牌机关代号 + 序号」，
 * 如「京A8K7D2」。车牌号本身属于公开可见的公共信息，仅用于测试数据填充。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"plate", "car-plate", "license-plate"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class CarPlateMockString implements MockString {

    /**
     * 省份简称池
     */
    private static final String[] PROVINCES = {
            "京", "津", "沪", "渝", "冀", "豫", "云", "辽", "黑", "湘",
            "皖", "鲁", "新", "苏", "浙", "赣", "鄂", "桂", "甘", "晋",
            "蒙", "陕", "吉", "闽", "贵", "粤", "青", "藏", "川", "宁",
            "琼"
    };
    /**
     * 发牌机关代号（大写字母，剔除易混淆的 I、O）
     */
    private static final char[] OFFICE_CODES = (
            "ABCDEFGHJKLMNPQRSTUVWXYZ").toCharArray();
    /**
     * 序号字符池（数字 + 大写字母，剔除易混淆的 I、O）
     */
    private static final char[] SERIAL_CHARS = (
            "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ").toCharArray();
    /**
     * 序号长度
     * @param environment 环境
     * @return 获取字符串的结果
     */
    private static final int SERIAL_LENGTH = 5;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(SERIAL_LENGTH + 2);
        builder.append(environment.randomOf(PROVINCES));
        builder.append(OFFICE_CODES[environment.nextInt(OFFICE_CODES.length)]);
        for (int i = 0; i < SERIAL_LENGTH; i++) {
            builder.append(SERIAL_CHARS[environment.nextInt(SERIAL_CHARS.length)]);
        }
        return builder.toString();
    }
}
