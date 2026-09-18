package com.chua.ibd.support.innodb;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneId;

/**
 * InnoDB 字段字节 → 可读值。
 *
 * <h3>三个最容易踩错的编码</h3>
 * <ol>
 *   <li><b>有符号整数把符号位翻转过</b>：InnoDB 为了让整数按字节比较就等于按数值比较，
 *       存储时把最高位取反。所以 {@code TINYINT} 里的 {@code 1} 存成 {@code 0x81}、
 *       {@code INT} 里的 {@code 76} 存成 {@code 0x8000004C}。照直读会得到
 *       {@code -127} 和 {@code -2147483572} 这种莫名其妙的值。
 *       无符号整数不翻转。</li>
 *   <li><b>{@code DECIMAL} 是紧凑二进制</b>：每 9 位十进制压进 4 字节，
 *       分段顺序是「整部前导残组 → 整部满组 → 小数满组 → 小数尾残组」，
 *       最高位当符号位。按定点小数直接除 100 会错。</li>
 *   <li><b>{@code TIMESTAMP} 存的是 UTC 秒</b>，要按本机时区渲染才和
 *       MySQL 客户端看到的一致（这也是 MySQL 客户端的行为）。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdTypeDecoder {

    /**
    * 十进制「每 9 位一组」对应的字节数表。
    */
    private static final int[] DIG2BYTES = {0, 1, 1, 2, 2, 3, 3, 4, 4, 4};

    /**
    * 工具类，禁止实例化。
    */
    private IbdTypeDecoder() {
    }

    /**
    * 解码一个字段（时间类按本机时区渲染）。
    *
    * @param column 字段定义
    * @param raw    字段原始字节
    * @return 可读值；{@code null} 表示 SQL NULL
    */
    public static Object decode(IbdColumn column, byte[] raw) {
        return decode(column, raw, ZoneId.systemDefault());
    }

    /**
    * 解码一个字段。
    *
    * @param column 字段定义
    * @param raw    字段原始字节
    * @param zone   渲染 {@code TIMESTAMP} 用的时区
    * @return 可读值；{@code null} 表示 SQL NULL
    */
    public static Object decode(IbdColumn column, byte[] raw, ZoneId zone) {
        if (raw == null) {
            return null;
        }
        if (column.systemColumn()) {
            return integer(raw, true);
        }
        switch (column.type()) {
            case TINY:
            case SHORT:
            case LONG:
            case LONGLONG:
            case INT24:
                return integer(raw, column.unsigned());
            case FLOAT:
                return raw.length < 4 ? 0.0d : (double) Float.intBitsToFloat((int) unsignedInt(raw, 0));
            case DOUBLE:
                return raw.length < 8 ? 0.0d : Double.longBitsToDouble(longValue(raw, 0));
            case YEAR:
                return raw.length < 1 || raw[0] == 0 ? 0L : (long) (1900 + (raw[0] & 0xFF));
            case DATE:
            case NEWDATE:
                return date(raw);
            case TIME:
                return legacyTime(raw);
            case DATETIME:
                return legacyDateTime(raw);
            case TIMESTAMP2:
                return timestamp(raw, column, zone);
            case DATETIME2:
                return dateTime2(raw, column);
            case TIME2:
                return time2(raw, column);
            case NEWDECIMAL:
                return decimal(raw, column.numericPrecision(), column.numericScale());
            case ENUM:
                return enumeration(raw, column);
            case SET:
                return set(raw, column);
            case BIT:
                return integer(raw, true);
            case VARCHAR:
            case VAR_STRING:
            case TINY_BLOB:
            case MEDIUM_BLOB:
            case LONG_BLOB:
            case BLOB:
            case JSON:
            case GEOMETRY:
                return text(column, raw);
            case STRING:
                // CHAR 在页里是按声明长度空格填充的，MySQL 取出来会去掉尾部空格；
                // 不去掉就会看到 'English             ' 这种带一堆尾空格的"脏"值
                return stripTrailingSpaces(text(column, raw));
            default:
                return "0x" + hex(raw);
        }
    }

    /**
    * 去掉尾部空格（仅用于 {@code CHAR}，且二进制字符集不处理）。
    *
    * @param value 原始文本
    * @return 去尾空格后的文本
    */
    private static String stripTrailingSpaces(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    /**
    * 解码整数。
    *
    * @param raw      原始字节
    * @param unsigned 是否无符号
    * @return {@code Long}；超出 {@code long} 范围（{@code BIGINT UNSIGNED} 高位段）时返回 {@link BigInteger}
    */
    public static Number integer(byte[] raw, boolean unsigned) {
        if (raw.length == 0) {
            return 0L;
        }
        BigInteger value = new BigInteger(1, raw);
        if (!unsigned) {
            value = value.subtract(BigInteger.ONE.shiftLeft(raw.length * 8 - 1));
        }
        if (value.bitLength() <= 63) {
            return value.longValue();
        }
        return value;
    }

    /**
    * 解码 {@code DATE}（3 字节：年 15 位、月 4 位、日 5 位）。
    *
    * @param raw 原始字节
    * @return {@code yyyy-MM-dd}
    */
    public static String date(byte[] raw) {
        if (raw.length < 3) {
            return "0000-00-00";
        }
        long value = bytesValue(raw) & 0xFFFFFFL;
        return String.format("%04d-%02d-%02d", value >> 9, (value >> 5) & 15, value & 31);
    }

    /**
    * 解码旧版 {@code TIME}（3 字节有符号整数，十进制位就是 {@code hhmmss}）。
    *
    * @param raw 原始字节
    * @return {@code hh:mm:ss}
    */
    public static String legacyTime(byte[] raw) {
        long value = signedInt(raw, 0, 3);
        String sign = value < 0 ? "-" : "";
        String digits = String.format("%06d", Math.abs(value));
        if (digits.length() > 6) {
            sign = sign + digits.substring(0, digits.length() - 6);
            digits = digits.substring(digits.length() - 6);
        }
        return sign + digits.substring(0, 2) + ":" + digits.substring(2, 4) + ":" + digits.substring(4, 6);
    }

    /**
    * 解码旧版 {@code DATETIME}（8 字节，十进制位就是 {@code YYYYMMDDhhmmss}）。
    *
    * @param raw 原始字节
    * @return {@code yyyy-MM-dd HH:mm:ss}
    */
    public static String legacyDateTime(byte[] raw) {
        if (raw.length < 8) {
            return "0000-00-00 00:00:00";
        }
        BigInteger value = new BigInteger(1, raw);
        BigInteger half = BigInteger.ONE.shiftLeft(63);
        if (value.compareTo(half) < 0) {
            return "0000-00-00 00:00:00";
        }
        String digits = value.subtract(half).toString();
        while (digits.length() < 14) {
            digits = "0" + digits;
        }
        return digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6, 8)
                + " " + digits.substring(8, 10) + ":" + digits.substring(10, 12) + ":" + digits.substring(12, 14);
    }

    /**
    * 解码 {@code TIMESTAMP}（4 字节 UTC 秒 + 小数秒）。
    *
    * @param raw    原始字节
    * @param column 字段定义（取小数秒精度）
    * @param zone   渲染时区
    * @return {@code yyyy-MM-dd HH:mm:ss[.fff]}
    */
    public static String timestamp(byte[] raw, IbdColumn column, ZoneId zone) {
        if (raw.length < 4) {
            return "1970-01-01 00:00:00";
        }
        long seconds = unsignedInt(raw, 0);
        java.time.LocalDateTime time = Instant.ofEpochSecond(seconds).atZone(zone).toLocalDateTime();
        return String.format("%04d-%02d-%02d %02d:%02d:%02d",
                time.getYear(), time.getMonthValue(), time.getDayOfMonth(),
                time.getHour(), time.getMinute(), time.getSecond())
                + fractional(raw, 4, column.datetimePrecision());
    }

    /**
    * 解码 {@code DATETIME}（5 字节：1 位符号 + 17 位「年*13+月」+ 5 位日 + 5 位时 + 6 位分 + 6 位秒）。
    *
    * @param raw    原始字节
    * @param column 字段定义（取小数秒精度）
    * @return {@code yyyy-MM-dd HH:mm:ss[.fff]}
    */
    public static String dateTime2(byte[] raw, IbdColumn column) {
        if (raw.length < 5) {
            return "0000-00-00 00:00:00";
        }
        long value = unsignedLong(raw, 0, 5) - 0x8000000000L;
        long yearMonth = value >> 22;
        return String.format("%04d-%02d-%02d %02d:%02d:%02d",
                yearMonth / 13, yearMonth % 13,
                (value >> 17) & 31, (value >> 12) & 31, (value >> 6) & 63, value & 63)
                + fractional(raw, 5, column.datetimePrecision());
    }

    /**
    * 解码 {@code TIME}（3 字节：1 位符号 + 1 位保留 + 10 位时 + 6 位分 + 6 位秒）。
    *
    * @param raw    原始字节
    * @param column 字段定义（取小数秒精度）
    * @return {@code hh:mm:ss[.fff]}
    */
    public static String time2(byte[] raw, IbdColumn column) {
        if (raw.length < 3) {
            return "00:00:00";
        }
        long value = (bytesValue(raw) & 0xFFFFFFL) - 0x800000L;
        String sign = value < 0 ? "-" : "";
        long abs = Math.abs(value);
        return sign + String.format("%02d:%02d:%02d", (abs >> 12) & 1023, (abs >> 6) & 63, abs & 63)
                + fractional(raw, 3, column.datetimePrecision());
    }

    /**
    * 拼小数秒。
    *
    * @param raw       原始字节
    * @param fromIndex 小数秒起始下标
    * @param precision 精度
    * @return 形如 {@code .123}；精度为 0 时返回空串
    */
    private static String fractional(byte[] raw, int fromIndex, int precision) {
        if (precision <= 0 || raw.length <= fromIndex) {
            return "";
        }
        int bytes = (precision + 1) / 2;
        int end = Math.min(raw.length, fromIndex + bytes);
        BigInteger value = new BigInteger(1, java.util.Arrays.copyOfRange(raw, fromIndex, end));
        String digits = value.toString();
        while (digits.length() < precision) {
            digits = "0" + digits;
        }
        return "." + digits.substring(0, precision);
    }

    /**
    * 解码 {@code DECIMAL}。
    *
    * @param raw       原始字节
    * @param precision 精度
    * @param scale     标度
    * @return {@link BigDecimal}
    */
    public static BigDecimal decimal(byte[] raw, int precision, int scale) {
        byte[] buffer = raw.clone();
        boolean negative;
        if (buffer.length > 0 && (buffer[0] & 0x80) != 0) {
            negative = false;
        } else {
            // 负数存的是正数表示按位取反
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] ^= 0xFF;
            }
            negative = true;
        }
        // 首字节的最高位是符号位，不属于数值 —— 两种情况下都要清掉。
        // 漏了这一步，负数的「整部前导残组」会多出 0x80：DECIMAL(4,2) 的 -0.99
        // （0x7F9C）会被解成 -128.99。sakila 里没有负小数，所以对拍发现不了。
        if (buffer.length > 0) {
            buffer[0] &= 0x7F;
        }
        int integerDigits = precision - scale;
        int integerFull = integerDigits / 9;
        int integerRemainder = integerDigits % 9;
        int fractionFull = scale / 9;
        int fractionRemainder = scale % 9;

        int position = 0;
        StringBuilder integer = new StringBuilder();
        if (integerRemainder > 0) {
            int size = DIG2BYTES[integerRemainder];
            integer.append(pad(beValue(buffer, position, size), integerRemainder));
            position += size;
        }
        for (int i = 0; i < integerFull; i++) {
            integer.append(pad(beValue(buffer, position, 4), 9));
            position += 4;
        }
        StringBuilder fraction = new StringBuilder();
        for (int i = 0; i < fractionFull; i++) {
            fraction.append(pad(beValue(buffer, position, 4), 9));
            position += 4;
        }
        if (fractionRemainder > 0) {
            int size = DIG2BYTES[fractionRemainder];
            fraction.append(pad(beValue(buffer, position, size), fractionRemainder));
            position += size;
        }
        BigInteger unscaled = new BigInteger((integer + fraction.toString()).isEmpty()
                ? "0" : (integer + fraction.toString()));
        if (negative && unscaled.signum() != 0) {
            unscaled = unscaled.negate();
        }
        return new BigDecimal(unscaled, scale);
    }

    /**
    * 把整数左侧补零到固定位数。
    *
    * @param value  数值
    * @param digits 目标位数
    * @return 补零后的字符串
    */
    private static String pad(long value, int digits) {
        String text = Long.toString(value);
        StringBuilder sb = new StringBuilder();
        for (int i = text.length(); i < digits; i++) {
            sb.append('0');
        }
        return sb.append(text).toString();
    }

    /**
    * 解码 {@code ENUM}。
    *
    * <p><b>踩过的坑</b>：这里曾经用 {@code unsignedInt(raw, 0)} 取序号，而这个方法会把不足
    * 4 字节的数组<b>左侧补零</b>成 4 字节 —— 于是 1 字节的 {@code 0x02}（{@code PG}）被读成
    * {@code 0x02000000} = 33554432，{@code film.rating} 整列输出成数字。ENUM 的宽度是
    * 1 或 2 字节，必须<b>按实际字节数</b>解释。</p>
    *
    * @param raw    原始字节
    * @param column 字段定义
    * @return 枚举文本；序号为 0（非法值）返回 {@code null}
    */
    public static String enumeration(byte[] raw, IbdColumn column) {
        long index = bytesValue(raw);
        if (index <= 0) {
            return null;
        }
        if (index > column.elements().size()) {
            return String.valueOf(index);
        }
        return column.elements().get((int) index - 1);
    }

    /**
    * 解码 {@code SET}。
    *
    * @param raw    原始字节
    * @param column 字段定义
    * @return 逗号分隔的成员；空集合返回空串
    */
    public static String set(byte[] raw, IbdColumn column) {
        long mask = bytesValue(raw);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < column.elements().size(); i++) {
            if ((mask & (1L << i)) != 0) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(column.elements().get(i));
            }
        }
        return sb.toString();
    }

    /**
    * 解码文本类字段。
    *
    * @param column 字段定义
    * @param raw    原始字节
    * @return 文本；二进制字符集（{@code collation_id = 63}）返回 {@code 0x...} 十六进制
    */
    public static String text(IbdColumn column, byte[] raw) {
        Charset charset = column.charset();
        if (charset == null) {
            return "0x" + hex(raw);
        }
        if (raw.length == 0) {
            return "";
        }
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(raw)).toString();
        } catch (Exception ignored) {
            return new String(raw, charset);
        }
    }

    /**
    * 把整个数组当作大端无符号整数读取（长度即宽度，最多 8 字节）。
    *
    * <p>与 {@link #unsignedInt} 的区别：<b>不做左侧补零</b>。{@code ENUM} / {@code SET}
    * 这类「宽度由元素个数决定」的定长整数必须用它，否则 1 字节的值会被左移 24 位。</p>
    *
    * @param raw 原始字节
    * @return 无符号值
    */
    private static long bytesValue(byte[] raw) {
        long value = 0;
        for (int i = 0; i < raw.length && i < 8; i++) {
            value = (value << 8) | (raw[i] & 0xFFL);
        }
        return value;
    }

    /**
    * 读任意字节数的大端无符号整数（最多 8 字节）。
    *
    * @param raw   原始字节
    * @param index 起始下标
    * @param size  字节数
    * @return 无符号值
    */
    private static long unsignedLong(byte[] raw, int index, int size) {
        long value = 0;
        for (int i = 0; i < size && i < 8; i++) {
            int at = index + i;
            value = (value << 8) | (at < raw.length ? (raw[at] & 0xFFL) : 0L);
        }
        return value;
    }

    /**
    * 读 4 字节无符号整数（大端）。
    *
    * <p><b>注意</b>：它按「4 字节定宽」解释，数组不够长时补的是<b>低位</b>零字节
    * （{@code 0F AC 4F} 会变成 {@code 0F AC 4F 00}），不是左侧补零。
    * 只适用于宽度确定就是 4 字节的场合（{@code TIMESTAMP} / {@code FLOAT}）。
    * 宽度由数据决定的（{@code ENUM} / {@code SET} / {@code DATE} / {@code TIME2}）
    * 必须用 {@link #bytesValue}。</p>
    *
    * @param raw   原始字节
    * @param index 起始下标
    * @return 无符号值
    */
    private static long unsignedInt(byte[] raw, int index) {
        return unsignedLong(raw, index, 4);
    }

    /**
    * 读 8 字节（大端）。
    *
    * @param raw   原始字节
    * @param index 起始下标
    * @return 值
    */
    private static long longValue(byte[] raw, int index) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            int at = index + i;
            value = (value << 8) | (at < raw.length ? (raw[at] & 0xFFL) : 0L);
        }
        return value;
    }

    /**
    * 读定长有符号整数（大端）。
    *
    * @param raw   原始字节
    * @param index 起始下标
    * @param size  字节数
    * @return 有符号值
    */
    private static long signedInt(byte[] raw, int index, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) {
            int at = index + i;
            value = (value << 8) | (at < raw.length ? (raw[at] & 0xFFL) : 0L);
        }
        long signBit = 1L << (size * 8 - 1);
        return (value & signBit) != 0 ? value - (signBit << 1) : value;
    }

    /**
    * 读大端无符号整数（最多 4 字节）。
    *
    * @param raw   原始字节
    * @param index 起始下标
    * @param size  字节数
    * @return 无符号值
    */
    private static long beValue(byte[] raw, int index, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) {
            int at = index + i;
            value = (value << 8) | (at < raw.length ? (raw[at] & 0xFFL) : 0L);
        }
        return value;
    }

    /**
    * 转十六进制串。
    *
    * @param raw 原始字节
    * @return 小写十六进制
    */
    public static String hex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
