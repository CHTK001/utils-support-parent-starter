package com.chua.common.support.math.unit.size;

import com.chua.common.support.utils.StringUtils;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据大小值，支持解析与格式化（如 12MB、5GB）。
 *
 * <p>提供从字符串解析数据大小、格式化为可读字符串、不同单位间转换的能力。</p>
 *
 * @author Sam Brannen, Stephane Nicoll
 * @since 4.0.0.42
 */
public final class SizeValue implements Comparable<SizeValue> {

    /**
     * 数据大小字符串解析正则
     *
     * <p>格式：可选正负号 + 数字（含小数）+ 可选单位后缀（0-2位字母）</p>
     */
    private static final Pattern PATTERN = Pattern.compile("^([+-]?\\d+(\\.\\d+)?)([a-zA-Z]{0,2})$");

    /**
     * 每 KB 字节数
     */
    private static final long BYTES_PER_KB = 1024;

    /**
     * 每 MB 字节数
     */
    private static final long BYTES_PER_MB = BYTES_PER_KB * 1024;

    /**
     * 每 GB 字节数
     */
    private static final long BYTES_PER_GB = BYTES_PER_MB * 1024;

    /**
     * 每 TB 字节数
     */
    private static final long BYTES_PER_TB = BYTES_PER_GB * 1024;

    /**
     * 字节数（内部统一以字节为单位）
     */
    private final long bytes;

    /**
     * 创建 SizeValue 实例
     * @param bytes bytes
     */
    private SizeValue(long bytes) {
        this.bytes = bytes;
    }

    /**
     * 从字节数创建 SizeValue
     *
     * @param bytes 字节数
     * @return SizeValue 实例
     */
    public static SizeValue ofBytes(long bytes) {
        return new SizeValue(bytes);
    }

    /**
     * 从千字节数创建 SizeValue
     *
     * @param kilobytes 千字节数
     * @return SizeValue 实例
     */
    public static SizeValue ofKilobytes(long kilobytes) {
        return new SizeValue(Math.multiplyExact(kilobytes, BYTES_PER_KB));
    }

    /**
     * 从兆字节数创建 SizeValue
     *
     * @param megabytes 兆字节数
     * @return SizeValue 实例
     */
    public static SizeValue ofMegabytes(long megabytes) {
        return new SizeValue(Math.multiplyExact(megabytes, BYTES_PER_MB));
    }

    /**
     * 从吉字节数创建 SizeValue
     *
     * @param gigabytes 吉字节数
     * @return SizeValue 实例
     */
    public static SizeValue ofGigabytes(long gigabytes) {
        return new SizeValue(Math.multiplyExact(gigabytes, BYTES_PER_GB));
    }

    /**
     * 从太字节数创建 SizeValue
     *
     * @param terabytes 太字节数
     * @return SizeValue 实例
     */
    public static SizeValue ofTerabytes(long terabytes) {
        return new SizeValue(Math.multiplyExact(terabytes, BYTES_PER_TB));
    }

    /**
     * 从数值和单位创建 SizeValue
     *
     * @param amount 数值
     * @param unit   单位，为 null 时默认字节
     * @return SizeValue 实例
     */
    public static SizeValue of(long amount, SizeUnit unit) {
        if (unit == null) {
            unit = SizeUnit.BYTES;
        }
        return new SizeValue(Math.multiplyExact(amount, unit.toByteSize()));
    }

    /**
     * 从 BigDecimal 数值和单位创建 SizeValue
     *
     * @param amount 数值
     * @param unit   单位，为 null 时默认字节
     * @return SizeValue 实例
     */
    public static SizeValue of(BigDecimal amount, SizeUnit unit) {
        if (unit == null) {
            unit = SizeUnit.BYTES;
        }
        return new SizeValue(amount.multiply(new BigDecimal(unit.toByteSize())).longValue());
    }

    /**
     * 从文本解析 SizeValue
     *
     * <p>支持格式如 "12KB"、"5MB"、"20"。</p>
     *
     * @param text 待解析文本
     * @return SizeValue 实例
     */
    public static SizeValue parse(CharSequence text) {
        return parse(text, null);
    }

    /**
     * 从文本解析 SizeValue，未指定单位时使用默认单位
     *
     * @param text        待解析文本
     * @param defaultUnit 默认单位
     * @return SizeValue 实例
     */
    public static SizeValue parse(CharSequence text, SizeUnit defaultUnit) {
        try {
            String newText = StringUtils.trimAllWhitespace(text.toString());
            Matcher matcher = PATTERN.matcher(newText);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("'" + text + "' is not a valid data size");
            }
            SizeUnit unit = determineDataUnit(matcher.group(3), defaultUnit);
            return SizeValue.of(new BigDecimal(matcher.group(1)), unit);
        } catch (Exception ex) {
            throw new IllegalArgumentException("'" + text + "' is not a valid data size", ex);
        }
    }

    /**
     * 确定数据单位
     *
     * @param suffix      单位后缀
     * @param defaultUnit 默认单位
     * @return 确定的 SizeUnit
     */
    private static SizeUnit determineDataUnit(String suffix, SizeUnit defaultUnit) {
        SizeUnit defaultUnitToUse = (defaultUnit != null ? defaultUnit : SizeUnit.BYTES);
        if (suffix != null && !suffix.isEmpty()) {
            return SizeUnit.fromSuffix(suffix);
        }
        return defaultUnitToUse;
    }

    /**
     * 将字节数格式化为可读字符串
     *
     * @param size 字节数
     * @return 格式化后的字符串（如 "1.5 MB"）
     */
    public static String format(long size) {
        if (size <= 0) {
            return "0";
        }
        int digitGroups = Math.min(SizeUnit.UNIT_NAMES.length - 1,
                (int) (Math.log10(size) / Math.log10(1024)));
        return new DecimalFormat("#,##0.0#")
                .format(size / Math.pow(1024, digitGroups))
                + " " + SizeUnit.UNIT_NAMES[digitGroups];
    }

    /**
     * 判断是否为负数
     *
     * @return 负数返回 true
     */
    public boolean isNegative() {
        return this.bytes < 0;
    }

    /**
     * ToByte获取大小
     * @return 结果数值
     */
    public long toByteSize() {
        return this.bytes;
    }

    /**
     * ToKilobyte获取大小
     * @return 结果数值
     */
    public long toKilobyteSize() {
        return this.bytes / BYTES_PER_KB;
    }

    /**
     * ToMegabyte获取大小
     * @return 结果数值
     */
    public long toMegabyteSize() {
        return this.bytes / BYTES_PER_MB;
    }

    /**
     * ToGigabyte获取大小
     * @return 结果数值
     */
    public long toGigabyteSize() {
        return this.bytes / BYTES_PER_GB;
    }

    /**
     * ToTerabyte获取大小
     * @return 结果数值
     */
    public long toTerabyteSize() {
        return this.bytes / BYTES_PER_TB;
    }

    @Override
    /**
     * 比较To
    */
    public int compareTo(SizeValue other) {
        return Long.compare(this.bytes, other.bytes);
    }

    @Override
    /**
     * ToString
    */
    public String toString() {
        return String.format("%dB", this.bytes);
    }

    @Override
    /**
     * 判断相等
    */
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        SizeValue otherSize = (SizeValue) other;
        return this.bytes == otherSize.bytes;
    }

    @Override
    /**
     * HashCode
    */
    public int hashCode() {
        return Long.hashCode(this.bytes);
    }
}
