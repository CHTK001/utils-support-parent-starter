package com.chua.common.support.file;

import java.text.DecimalFormat;

/**
 * 文件大小工具类，提供文件大小格式化和解析功能。
 *
 * <p>支持字节（B）、千字节（KB）、兆字节（MB）、吉字节（GB）、太字节（TB）<br>
 * 之间的相互转换和人类可读格式输出。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 格式化输出
 * FileSize.of(1024).toHumanReadable();        // "1.00 KB"
 * FileSize.of(1_073_741_824L).toHumanReadable(); // "1.00 GB"
 *
 * // 友好显示（自动选择单位）
 * FileSize.format(1234567);  // "1.18 MB"
 *
 * // 解析字符串
 * FileSize.parse("2.5 GB");  // 2684354560L
 * }</pre>
 *
 * @author CH
 * @since 1.0
 */
public class FileSize {

    /** 字节数 */
    private final long bytes;

    /** 1024 = 1 KB */
    public static final long KB = 1024;
    /** 1024^2 = 1 MB */
    public static final long MB = KB * 1024;
    /** 1024^3 = 1 GB */
    public static final long GB = MB * 1024;
    /** 1024^4 = 1 TB */
    public static final long TB = GB * 1024;
    /** 1024^5 = 1 PB */
    public static final long PB = TB * 1024;

    /** 数字格式化器 */
    private static final DecimalFormat DF = new DecimalFormat("#.00");
    /** 文件容量单位数组 */
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    /**
     * 构造函数。
     *
     * @param bytes 字节数
     */
    private FileSize(long bytes) {
        this.bytes = bytes;
    }

    /**
     * 通过字节数创建 FileSize 实例。
     *
     * @param bytes 字节数
     * @return FileSize 实例
     */
    public static FileSize of(long bytes) {
        return new FileSize(bytes);
    }

    /**
     * 获取字节数。
     *
     * @return 字节数
     */
    public long getBytes() {
        return bytes;
    }

    /**
     * 获取 KB 数（向上取整）。
     *
     * @return KB 数
     */
    public long getKb() {
        return bytes / KB;
    }

    /**
     * 获取 MB 数（向上取整）。
     *
     * @return MB 数
     */
    public long getMb() {
        return bytes / MB;
    }

    /**
     * 获取 GB 数（向上取整）。
     *
     * @return GB 数
     */
    public long getGb() {
        return bytes / GB;
    }

    /**
     * 格式化为人类可读的大小字符串（自动选择单位）。
     *
     * <p>例如：1024 → "1.00 KB"，1_073_741_824 → "1.00 GB"</p>
     *
     * @return 格式化后的字符串
     */
    public String toHumanReadable() {
        if (bytes == 0) {
            return "0 B";
        }

        int unitIndex = 0;
        long remaining = bytes;
        while (remaining >= 1024 && unitIndex < UNITS.length - 1) {
            remaining /= 1024;
            unitIndex++;
        }

        if (unitIndex == 0) {
            return bytes + " B";
        }

        double value = (double) bytes / Math.pow(1024, unitIndex);
        return DF.format(value) + " " + UNITS[unitIndex];
    }

    /**
     * 格式化为指定单位的大小字符串。
     *
     * @param unit 目标单位（"B", "KB", "MB", "GB", "TB"）
     * @return 格式化后的字符串
     */
    public String toHumanReadable(String unit) {
        String unitUpper = unit.toUpperCase();
        double value;
        switch (unitUpper) {
            case "B":
                value = bytes;
                break;
            case "KB":
                value = (double) bytes / KB;
                break;
            case "MB":
                value = (double) bytes / MB;
                break;
            case "GB":
                value = (double) bytes / GB;
                break;
            case "TB":
                value = (double) bytes / TB;
                break;
            case "PB":
                value = (double) bytes / PB;
                break;
            default:
                throw new IllegalArgumentException("不支持的单位: " + unit);
        }
        return DF.format(value) + " " + unitUpper;
    }

    /**
     * 解析人类可读的大小字符串为字节数。
     *
     * <p>支持格式：{@code "1024"}、{@code "1 KB"}、{@code "2.5 MB"}、{@code "1.5GB"} 等。</p>
     *
     * @param readable 可读大小字符串
     * @return 字节数
     * @throws IllegalArgumentException 解析失败时抛出
     */
    public static long parse(String readable) {
        if (readable == null || readable.trim().isEmpty()) {
            throw new IllegalArgumentException("大小字符串不能为空");
        }

        String trimmed = readable.trim().toUpperCase();

        // 提取数字部分和单位部分
        StringBuilder numStr = new StringBuilder();
        StringBuilder unitStr = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == ',' || c == '-' || c == '+') {
                numStr.append(c == ',' ? '.' : c);
            } else if (c != ' ') {
                unitStr.append(c);
            }
        }

        if (numStr.length() == 0) {
            throw new IllegalArgumentException("无法解析大小: " + readable);
        }

        double value = Double.parseDouble(numStr.toString());
        String unit = unitStr.length() > 0 ? unitStr.toString() : "B";

        switch (unit) {
            case "B":
                return (long) value;
            case "KB":
            case "K":
                return (long) (value * KB);
            case "MB":
            case "M":
                return (long) (value * MB);
            case "GB":
            case "G":
                return (long) (value * GB);
            case "TB":
            case "T":
                return (long) (value * TB);
            case "PB":
            case "P":
                return (long) (value * PB);
            default:
                throw new IllegalArgumentException("不支持的单位: " + unit);
        }
    }

    /**
     * 快速格式化文件大小为人类可读字符串（静态便捷方法）。
     *
     * @param bytes 字节数
     * @return 格式化后的字符串
     */
    public static String format(long bytes) {
        return of(bytes).toHumanReadable();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        FileSize size = (FileSize) o;
        return bytes == size.bytes;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(bytes);
    }

    @Override
    public String toString() {
        return toHumanReadable();
    }
}
