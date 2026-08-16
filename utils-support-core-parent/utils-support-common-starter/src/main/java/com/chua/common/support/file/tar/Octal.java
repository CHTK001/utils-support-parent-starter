package com.chua.common.support.file.tar;


/**
 * 八进制数值转换工具类，用于 Tar 头部字段的解析与写入。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Octal {

    /**
     * 从头部缓冲区解析八进制字符串。此方法用于文件权限模式值的解析。
     *
     * @param header 从中解析的头部缓冲区。
     * @param offset 从中开始解析的缓冲区偏移量。
     * @param length 要解析的头部字节数。
     * @return 八进制字符串对应的长整型值。
     */
    public static long parseOctal(byte[] header, int offset, int length) {
        long result = 0;
        boolean stillPadding = true;

        int end = offset + length;
        for (int i = offset; i < end; ++i) {
            if (header[i] == 0) {
                break;
            }

            if (header[i] == (byte) ' ' || header[i] == '0') {
                if (stillPadding) {
                    continue;
                }

                if (header[i] == (byte) ' ') {
                    break;
                }
            }

            stillPadding = false;

            result = (result << 3) + (header[i] - '0');
        }

        return result;
    }

    /**
     * 将八进制整数写入头部缓冲区。
     *
     * @param value  要写入的值。
     * @param buf    写入的目标头部缓冲区。
     * @param offset 在缓冲区中的起始偏移量。
     * @param length 要写入的字节数。
     * @return 写入操作完成后的下一个位置索引。
     */
    public static int getOctalBytes(long value, byte[] buf, int offset, int length) {
        int idx = length - 1;

        buf[offset + idx] = 0;
        --idx;
        buf[offset + idx] = (byte) ' ';
        --idx;

        if (value == 0) {
            buf[offset + idx] = (byte) '0';
            --idx;
        } else {
            for (long val = value; idx >= 0 && val > 0; --idx) {
                buf[offset + idx] = (byte) ('0' + (val & 7));
                val = val >> 3;
            }
        }

        for (; idx >= 0; --idx) {
            buf[offset + idx] = (byte) '0';
        }

        return offset + length;
    }

    /**
     * 将校验和八进制整数写入头部缓冲区。
     *
     * @param value  要写入的值。
     * @param buf    写入的目标头部缓冲区。
     * @param offset 在缓冲区中的起始偏移量。
     * @param length 要写入的字节数。
     * @return 条目校验和的整数值（实际返回的是写入结束的位置）。
     */
    public static int getCheckSumOctalBytes(long value, byte[] buf, int offset, int length) {
        getOctalBytes(value, buf, offset, length);
        buf[offset + length - 1] = (byte) ' ';
        buf[offset + length - 2] = 0;
        return offset + length;
    }

    /**
     * 将八进制长整型整数写入头部缓冲区。
     *
     * @param value  要写入的值。
     * @param buf    写入的目标头部缓冲区。
     * @param offset 在缓冲区中的起始偏移量。
     * @param length 要写入的字节数。
     * @return 写入操作完成后的下一个位置索引。
     */
    public static int getLongOctalBytes(long value, byte[] buf, int offset, int length) {
        byte[] temp = new byte[length + 1];
        getOctalBytes(value, temp, 0, length + 1);
        System.arraycopy(temp, 0, buf, offset, length);
        return offset + length;
    }

}
