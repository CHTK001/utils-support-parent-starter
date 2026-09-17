package com.chua.common.support.utils;


import static com.chua.common.support.utils.ByteUtils.*;


/**
 * 位运算工具类，提供基础类型与二进制位字符串之间的转换与拼接能力。
 * <p>
 * 该类可将 {@code byte}、{@code short}、{@code int}、{@code long}、{@code float}、{@code double}
 * 转换为二进制位数组或位字符串，亦可对二进制字符串进行补齐、截断和分段拼接，适用于协议解析、调试和位运算场景。
 *
 * @author CH
 * @版本 1.0.0
 * @since 2021/3/3
*/
public class BitUtils {

    /**
    * 钻头工具。
    */
    protected BitUtils() {}

    /**
    * 单个字节对应的二进制位数。
    */
    public static final int BIT_LENGTH = 8;
    /**
    * 单个字节的长度，单位为字节。
    */
    public static final int BYTE_LENGTH = 1;
    /** Byte_大小 */
    public static final int BYTE_SIZE = BYTE_LENGTH;
    /** Byte_钻头_大小 */
    public static final int BYTE_BIT_SIZE = BIT_LENGTH;
    /** Int_大小 */
    public static final int INT_SIZE = 4 * BYTE_SIZE;
    /** Int_钻头_大小 */
    public static final int INT_BIT_SIZE = 4 * BYTE_BIT_SIZE;
    /** Float_大小 */
    public static final int FLOAT_SIZE = INT_SIZE;
    /** Long_大小 */
    public static final int LONG_SIZE = 8 * BYTE_SIZE;
    /** Long_钻头_大小 */
    public static final int LONG_BIT_SIZE = 8 * BYTE_BIT_SIZE;
    /** Double_大小 */
    public static final int DOUBLE_SIZE = LONG_SIZE;
    /** Double_钻头_大小 */
    public static final int DOUBLE_BIT_SIZE = LONG_BIT_SIZE;
    /** Short_大小 */
    public static final int SHORT_SIZE = 2 * BYTE_SIZE;
    /** Short_钻头_大小 */
    public static final int SHORT_BIT_SIZE = 2 * BYTE_BIT_SIZE;
    /** Char_大小 */
    public static final int CHAR_SIZE = SHORT_SIZE;
    /** Char_钻头_大小 */
    public static final int CHAR_BIT_SIZE = SHORT_BIT_SIZE;
    /** 空_bytes */
    private static final byte[] EMPTY_BYTES = new byte[0];

    /** Symbol_blank */
    private static final String SYMBOL_BLANK = " ";

    /**
    * 将单个字节转换为 8 位二进制位数组。
    *
    * @param b 源字节值
    * @return 8 位二进制位数组
    */
    public static byte[] asBit(byte b) {
        byte[] byteArr = new byte[8];
        int size = BYTE_BIT_SIZE - 1;
        for (int i = size; i >= 0; i--) {
            //               
            byteArr[i] = (byte) (b & 0x01);
            //                  
            b = (byte) (b >> 1);
        }
        return byteArr;
    }

    /**
    * 将字节数组转换为对应的二进制位数组。
    *
    * @param b 源字节数组
    * @return 对应的二进制位数组
    */
    public static byte[] asBit(byte[] b) {
        int length = b.length;
        if (length == BYTE_SIZE) {
            return asBit(b[0]);
        }
        if (length == SHORT_SIZE) {
            return asBit(toShort(b));
        }
        if (length == INT_SIZE) {
            return asBit(toInt(b));
        }

        if (length == LONG_SIZE) {
            return asBit(toLong(b));
        }

        byte[] result = new byte[length];
        if (length % LONG_SIZE == 0) {
            for (int i = 0; i < b.length; i += LONG_SIZE) {
                byte[] bytes = asBit(toLong(b));
                System.arraycopy(bytes, 0, result, i * LONG_SIZE, LONG_SIZE);
            }
        }

        if (length % INT_SIZE == 0) {
            for (int i = 0; i < b.length; i += INT_SIZE) {
                byte[] bytes = asBit(toInt(b));
                System.arraycopy(bytes, 0, result, i * INT_SIZE, INT_SIZE);
            }
        }

        if (length % SHORT_SIZE == 0) {
            for (int i = 0; i < b.length; i += SHORT_SIZE) {
                byte[] bytes = asBit(toShort(b));
                System.arraycopy(bytes, 0, result, i * SHORT_SIZE, SHORT_SIZE);
            }
        }

        for (int i = 0; i < b.length; i++) {
            byte[] bytes = asBit(b[i]);
            System.arraycopy(bytes, 0, result, i * BYTE_SIZE, BYTE_SIZE);

        }
        return result;
    }

    /**
    * 将单个字节转换为 8 位二进制字符串。
    *
    * @param b 源字节值
    * @return 8 位二进制字符串
    */
    public static String asBitString(byte b) {
        byte[] bytes = asBit(b);
        return join(bytes, 8);
    }

    /**
    * 将二进制字符串数组拼接为位字符串。
    *
    * @param source 二进制字符串数组
    * @return 拼接后的位字符串
    */
    public static String asBitString(String[] source) {
        return join(asStringBytes(source), 4);
    }

    /**
    * 将整数二进制字符串补齐为 32 位，或截断为 32 位。
    *
    * @param source 源二进制字符串
    * @return 32 位二进制字符串
    */
    public static String asBitIntString(String source) {
        int length = source.length();
        if (length < INT_BIT_SIZE) {
            source = StringUtils.repeat("0", INT_BIT_SIZE - source.length()).concat(source);
        } else if (length > INT_BIT_SIZE) {
            source = source.substring(length - INT_BIT_SIZE, length);
        }
        return asBitString(source.split(""));
    }

    /**
    * 将长整型二进制字符串补齐为 64 位，或截断为 64 位。
    *
    * @param source 源二进制字符串
    * @return 64 位二进制字符串
    */
    public static String asBitLongString(String source) {
        int length = source.length();
        if (length < LONG_BIT_SIZE) {
            source = StringUtils.repeat("0", LONG_BIT_SIZE - source.length()).concat(source);
        } else if (length > LONG_BIT_SIZE) {
            source = source.substring(length - LONG_BIT_SIZE, length);
        }
        return asBitString(source.split(""));
    }

    /**
    * 将短整型二进制字符串补齐为 16 位，或截断为 16 位。
    *
    * @param source 源二进制字符串
    * @return 16 位二进制字符串
    */
    public static String asBitShortString(String source) {
        int length = source.length();
        if (length < SHORT_BIT_SIZE) {
            source = StringUtils.repeat("0", SHORT_BIT_SIZE - source.length()).concat(source);
        } else if (length > SHORT_BIT_SIZE) {
            source = source.substring(length - SHORT_BIT_SIZE, length);
        }
        return asBitString(source.split(""));
    }

    /**
    * 将字节二进制字符串补齐为 8 位，或截断为 8 位。
    *
    * @param source 源二进制字符串
    * @return 8 位二进制字符串
    */
    public static String asBitByteString(String source) {
        int length = source.length();
        if (length < BYTE_BIT_SIZE) {
            source = StringUtils.repeat("0", BYTE_BIT_SIZE - source.length()).concat(source);
        } else if (length > BYTE_BIT_SIZE) {
            source = source.substring(length - BYTE_BIT_SIZE, length);
        }
        return asBitString(source.split(""));
    }

    /**
    * 将短整型值转换为 16 位二进制位数组。
    *
    * @param b 源短整型值
    * @return 16 位二进制位数组
    */
    public static byte[] asBit(short b) {
        byte[] bytes = asBytes(b);
        byte[] item = new byte[SHORT_BIT_SIZE];
        int index = 0;
        for (byte aByte : bytes) {
            byte[] bytes1 = asBit(aByte);
            System.arraycopy(bytes1, 0, item, index++ * 8, bytes1.length);
        }
        return item;
    }

    /**
    * 将短整型值转换为 16 位二进制字符串。
    *
    * @param b 源短整型值
    * @return 16 位二进制字符串
    */
    public static String asBitString(short b) {
        byte[] bytes = asBit(b);
        return join(bytes, 8);
    }

    /**
    * 将长整型值转换为 64 位二进制位数组。
    *
    * @param b 源长整型值
    * @return 64 位二进制位数组
    */
    public static byte[] asBit(long b) {
        byte[] bytes = asBytes(b);
        byte[] item = new byte[8 * 8];
        int index = 0;
        for (byte aByte : bytes) {
            byte[] bytes1 = asBit(aByte);
            System.arraycopy(bytes1, 0, item, index++ * 8, bytes1.length);
        }
        return item;
    }

    /**
    * 将长整型值转换为 64 位二进制字符串。
    *
    * @param b 源长整型值
    * @return 64 位二进制字符串
    */
    public static String asBitString(long b) {
        byte[] bytes = asBit(b);
        return join(bytes, 8);
    }

    /**
    * 将单精度浮点数转换为 32 位二进制位数组。
    *
    * @param b 源单精度浮点数
    * @return 32 位二进制位数组
    */
    public static byte[] asBit(float b) {
        byte[] bytes = asBytes(b);
        byte[] item = new byte[4 * 8];
        int index = 0;
        for (byte aByte : bytes) {
            byte[] bytes1 = asBit(aByte);
            System.arraycopy(bytes1, 0, item, index++ * 8, bytes1.length);
        }
        return item;
    }

    /**
    * 将单精度浮点数转换为 32 位二进制字符串。
    *
    * @param b 源单精度浮点数
    * @return 32 位二进制字符串
    */
    public static String asBitString(float b) {
        byte[] bytes = asBit(b);
        return join(bytes, 8);
    }

    /**
    * 将双精度浮点数转换为 64 位二进制位数组。
    *
    * @param b 源双精度浮点数
    * @return 64 位二进制位数组
    */
    public static byte[] asBit(double b) {
        byte[] bytes = asBytes(b);
        byte[] item = new byte[8 * 8];
        int index = 0;
        for (byte aByte : bytes) {
            byte[] bytes1 = asBit(aByte);
            System.arraycopy(bytes1, 0, item, index++ * 8, bytes1.length);
        }
        return item;
    }

    /**
    * 将双精度浮点数转换为 64 位二进制字符串。
    *
    * @param b 源双精度浮点数
    * @return 64 位二进制字符串
    */
    public static String asBitString(double b) {
        byte[] bytes = asBit(b);
        return join(bytes, 8);
    }

    /**
    * 将整型值转换为 32 位二进制位数组。
    *
    * @param b 源整型值
    * @return 32 位二进制位数组
    */
    public static byte[] asBit(int b) {
        byte[] bytes = asBytes(b);
        byte[] item = new byte[4 * 8];
        int index = 0;
        for (byte aByte : bytes) {
            byte[] bytes1 = asBit(aByte);
            System.arraycopy(bytes1, 0, item, index++ * 8, bytes1.length);
        }
        return item;
    }

    /**
    * 将整型值转换为 32 位二进制字符串。
    *
    * @param b 源整型值
    * @return 32 位二进制字符串
    */
    public static String asBitString(int b) {
        byte[] bytes = asBit(b);
        return join(bytes, 8);
    }


    /**
    * 将位数组按指定长度分段拼接为字符串。
    *
    * @param bytes 源位数组
    * @param size 每段的长度
    * @return 拼接后的字符串
    */
    public static String join(byte[] bytes, int size) {
        if (null == bytes || size < 0) {
            return "";
        }

        int sourceLength = bytes.length;
        StringBuilder stringBuilder = new StringBuilder();
        if (sourceLength <= size) {
            for (byte aByte : bytes) {
                stringBuilder.append(aByte);
            }
            return stringBuilder.toString();
        }

        int quotient = sourceLength / size;
        int mold = sourceLength % size;

        for (int i = 0; i < quotient; i++) {
            for (int j = i * size; j < size * (i + 1); j++) {
                byte aByte = bytes[j];
                stringBuilder.append(aByte);
            }
            stringBuilder.append(SYMBOL_BLANK);
        }

        if (mold != 0) {
            StringBuilder stringBuilder1 = new StringBuilder();
            for (int i = sourceLength; i > mold; i--) {
                stringBuilder1.append(bytes[i]);
            }
            stringBuilder.append(SYMBOL_BLANK).append(StringUtils.repeat("0", size - mold)).append(stringBuilder1);
        }
        String resource = stringBuilder.toString();
        return resource.endsWith(SYMBOL_BLANK) ? resource.substring(0, resource.length() - 1) : resource;
    }

    /**
    * 将字符串数组转换为字节数组。
    *
    * @param source 源字符串数组
    * @return 转换后的字节数组
    */
    private static byte[] asStringBytes(String[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            String b = source[i];
            result[i] = Byte.parseByte(b);
        }
        return result;
    }
}
