package com.chua.common.support.utils;

import com.chua.common.support.lang.algorithm.crypto.Hex;
import com.google.common.base.Joiner;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.chua.common.support.constant.ValueConstant.SYMBOL_EMPTY_BYTE_ARRAY;
import static java.nio.charset.StandardCharsets.UTF_8;


/**
 * 提供字节数组与常见基本类型之间相互转换的工具类。
 * <p>
 * 该类封装了十六进制字符串解析、字节序转换、补码计算以及数值类型与字节数组之间的互转功能，
 * 适用于底层协议解析、数据序列化和字节流处理等场景。
 *
 * @author CH
 * @since 2020/12/26
 */
@Slf4j
public class ByteUtils extends BitUtils {

    /**
     * byte工具。
     */
    private ByteUtils() {}

    /**
     * Digits
    */
    private static final char[] DIGITS = new char[]{'a', 'b', 'c', '0', '1', 'C', 'D', '2', '3', '4', 'N', 'O', 'P', 'Q', '5', 'G', 'H', '6', 'U', 'V', '7', 'o', 'p', 'q', '8', 'W', 'X', '9',
            'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            'A', 'B', 'E', 'F', 'I', 'J', 'K', 'L', 'M', 'R', 'S', 'T', 'Y', 'Z',
            '-', '_'};

    /**
     * 将索引位置转换为对应的十六进制数字字符。
     *
     * @param i 需要转换的字符索引
     * @return 对应的字符
     */
    public static char toDigit(int i) {
        return DIGITS[i];
    }

    /**
     * 将十六进制字符串解析为字节数组。
     * <p>
     * 当输入为空时返回 {@code null}；若字符串长度为奇数或者不是合法的十六进制串则返回 {@code null}。
     *
     * @param s 待解析的十六进制字符串
     * @return 解析后的字节数组，若输入无效则返回 {@code null}
     */
    public static byte[] parseHexStringToArray(String s) {
        if (StringUtils.isEmpty(s)) {
            return null;
        }
        int len = s.length();
        if (len == 1) {
            byte[] tmp = new byte[1];
            tmp[0] = parseHexString(s);
            return tmp;
        }
        if (len % 2 != 0) {
            return null;
        }
        int size = len / 2;
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            String sub = s.substring(i * 2, i * 2 + 2);
            data[i] = parseHexString(sub);
        }
        return data;
    }

    /**
     * 将单个十六进制字符序列转换为一个字节值。
     *
     * @param s 单个十六进制字符串，例如 "FF"
     * @return 转换后的字节值
     */
    public static byte parseHexString(String s) {
        int i = Integer.parseInt(s, 16);
        return (byte) i;
    }

    /**
     * 将单个字节转换为大写十六进制字符串。
     *
     * @param b 待转换的字节
     * @return 二位大写十六进制字符串
     */
    public static String toHexString(byte b) {
        String s = Integer.toHexString(b & 0xFF);
        int len = s.length();
        if (len < 2) {
            s = "0" + s;
        }
        return s.toUpperCase();
    }

    /**
     * 将字节数组转换为带前后缀的十六进制字符串。
     *
     * @param bytes 需要转换的字节数组
     * @param prefix 每个字节前缀，如 "0x"
     * @param suffix 每个字节后缀，如 " "
     * @return 转换后的字符串，输入为空时返回 {@code null}
     */
    public static String toHexString(byte[] bytes, String prefix, String suffix) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(toHexString(b, prefix, suffix));
        }
        return sb.toString();
    }

    /**
     * 将字节数组转换为无分隔符的十六进制字符串。
     *
     * @param bytes 需要转换的字节数组
     * @return 转换后的十六进制字符串，输入为空时返回 {@code null}
     */
    public static String toHexString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(toHexString(b));
        }
        return sb.toString();
    }

    /**
     * 将单个字节转换为指定前后缀包裹的十六进制字符串。
     *
     * @param b 待转换的字节
     * @param prefix 每个字节前缀
     * @param suffix 每个字节后缀
     * @return 包含前后缀的十六进制字符串
     */
    public static String toHexString(byte b, String prefix, String suffix) {
        String s = Integer.toHexString(b & 0xFF);
        int len = s.length();
        if (len < 2) {
            s = "0" + s;
        }
        s = prefix + s + suffix;
        return s.toUpperCase();
    }

    /**
     * 将双精度浮点数转换为字节数组。
     *
     * @param source 源双精度浮点数
     * @return 对应的字节数组
     */
    public static byte[] asBytes(double source) {
        try {
            return ByteBuffer.allocate(DOUBLE_SIZE).putDouble(source).array();
        } catch (Exception e) {
            long l = Double.doubleToRawLongBits(source);
            return asBytes(l);
        }
    }

    /**
     * 从字节缓冲区中读取剩余可用字节并转换为字节数组。
     *
     * @param buffer 源字节缓冲区
     * @return 剩余字节内容
     */
    public static byte[] asBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    /**
     * 将单精度浮点数转换为字节数组。
     *
     * @param source 源单精度浮点数
     * @return 对应的字节数组
     */
    public static byte[] asBytes(float source) {
        try {
            return ByteBuffer.allocate(FLOAT_SIZE).putFloat(source).array();
        } catch (Exception e) {
            int i = Float.floatToIntBits(source);
            return asBytes(i);
        }
    }

    /**
     * 将长整型数值转换为字节数组。
     *
     * @param source 源长整型值
     * @return 对应的字节数组
     */
    public static byte[] asBytes(long source) {
        try {
            return ByteBuffer.allocate(LONG_SIZE).putLong(source).array();
        } catch (Exception e) {
            byte[] result = new byte[LONG_SIZE];
            result[0] = (byte) ((source >> 56) & 0xFF);
            result[1] = (byte) ((source >> 48) & 0xFF);
            result[2] = (byte) ((source >> 40) & 0xFF);
            result[3] = (byte) ((source >> 32) & 0xFF);
            result[4] = (byte) ((source >> 24) & 0xFF);
            result[5] = (byte) ((source >> 16) & 0xFF);
            result[6] = (byte) ((source >> 8) & 0xFF);
            result[7] = (byte) (source & 0xFF);
            return result;
        }
    }

    /**
     * 将短整型数值转换为字节数组。
     *
     * @param source 源短整型值
     * @return 对应的字节数组
     */
    public static byte[] asBytes(short source) {
        try {
            return ByteBuffer.allocate(SHORT_SIZE).putShort(source).array();
        } catch (Exception e) {
            byte[] result = new byte[SHORT_SIZE];
            result[0] = (byte) ((source >> 8) & 0xFF);
            result[1] = (byte) (source & 0xFF);
            return result;
        }
    }

    /**
     * 将整型数值转换为字节数组。
     *
     * @param source 源整型值
     * @return 对应的字节数组
     */
    public static byte[] asBytes(int source) {
        try {
            return ByteBuffer.allocate(INT_SIZE).putInt(source).array();
        } catch (Exception e) {
            byte[] result = new byte[INT_SIZE];
            result[0] = (byte) ((source >> 24) & 0xFF);
            result[1] = (byte) ((source >> 16) & 0xFF);
            result[2] = (byte) ((source >> 8) & 0xFF);
            result[3] = (byte) (source & 0xFF);
            return result;
        }

    }

    /**
     * 将字符转换为字节数组。
     *
     * @param c 源字符
     * @return 对应的字节数组
     */
    public static byte[] asBytes(char c) {
        try {
            return ByteBuffer.allocate(CHAR_SIZE).putChar(c).array();
        } catch (Exception e) {
            byte[] b = new byte[CHAR_SIZE];
            b[0] = (byte) ((c & 0xff00) >> 8);
            b[1] = (byte) (c & 0x00ff);
            return b;
        }
    }

    /**
     * 将字符串转换为 UTF-8 字节数组。
     *
     * @param source 源字符串
     * @return 对应的 UTF-8 字节数组，输入为空时返回空字节数组常量
     */
    public static byte[] asBytes(String source) {
        if (StringUtils.isNullOrEmpty(source)) {
            return SYMBOL_EMPTY_BYTE_ARRAY;
        }
        return source.getBytes(UTF_8);
    }

    /**
     * 将布尔值转换为字节数组。
     *
     * @param source 源布尔值
     * @return 以整数形式编码的字节数组，{@code true} 对应 1，{@code false} 对应 0
     */
    public static byte[] asBytes(boolean source) {
        int tmp = !source ? 0 : 1;
        return ByteBuffer.allocate(INT_SIZE).putInt(tmp).array();
    }


    /**
     *       
     *
     * @param b       
     * @return       
     */
    public static byte[] complementArithmetic(int b) {
        int bit = INT_SIZE * BIT_LENGTH;
        List<String> split = Arrays.stream(asBitIntString(Integer.toBinaryString(-b)).split("")).filter(item -> !StringUtils.isNullOrEmpty(item)).collect(Collectors.toList());
        log.info("\n{}(         ) \n-> {}(      ) \n-> {}(      ) \n-> {}(      )", b, asBitIntString(Integer.toBinaryString(b)), asBitIntString(Integer.toBinaryString(~b)), asBitIntString(Joiner.on("").join(split)));
        byte[] bytes = new byte[bit];
        for (int i = 0; i < split.size(); i++) {
            String s = split.get(i);
            bytes[i] = Byte.valueOf(s);
        }
        return bytes;
    }

    /**
     *       
     *
     * @param b       
     * @return       
     */
    public static byte[] complementArithmetic(long b) {
        int bit = INT_SIZE * BIT_LENGTH;
        List<String> split = Arrays.stream(asBitLongString(Long.toBinaryString(-b)).split("")).filter(item -> !StringUtils.isNullOrEmpty(item)).collect(Collectors.toList());
        log.info("\n{}(         ) \n-> {}(      ) \n-> {}(      ) \n-> {}(      )", b, asBitLongString(Long.toBinaryString(b)), asBitLongString(Long.toBinaryString(~b)), asBitLongString(Joiner.on("").join(split)));
        byte[] bytes = new byte[bit];
        for (int i = 0; i < split.size(); i++) {
            String s = split.get(i);
            bytes[i] = Byte.valueOf(s);
        }
        return bytes;
    }

    /**
     *       
     *
     * @param b       
     * @return       
     */
    public static byte[] complementArithmetic(short b) {
        int bit = SHORT_SIZE * BIT_LENGTH;
        List<String> split = Arrays.stream(asBitShortString(Integer.toBinaryString(-b)).split("")).filter(item -> !StringUtils.isNullOrEmpty(item.trim())).collect(Collectors.toList());
        log.info("\n{}(         ) \n-> {}(      ) \n-> {}(      ) \n-> {}(      )", b, asBitShortString(Integer.toBinaryString(b)), asBitShortString(Integer.toBinaryString(~b)), asBitShortString(Joiner.on("").join(split)));
        byte[] bytes = new byte[bit];
        for (int i = 0; i < split.size(); i++) {
            String s = split.get(i);
            bytes[i] = Byte.valueOf(s);
        }
        return bytes;
    }

    /**
     *       
     *
     * @param b       
     * @return       
     */
    public static byte[] complementArithmetic(byte b) {
        int bit = BYTE_SIZE * BIT_LENGTH;
        List<String> split = Arrays.stream(asBitByteString(Integer.toBinaryString(-b)).split("")).filter(item -> !StringUtils.isNullOrEmpty(item)).collect(Collectors.toList());
        log.info("\n{}(         ) \n-> {}(      ) \n-> {}(      ) \n-> {}(      )", b, asBitByteString(Integer.toBinaryString(b)), asBitByteString(Integer.toBinaryString(~b)), asBitByteString(Joiner.on("").join(split)));
        byte[] bytes = new byte[bit];
        for (int i = 0; i < split.size(); i++) {
            String s = split.get(i);
            bytes[i] = Byte.valueOf(s);
        }
        return bytes;
    }

    /**
     *          
     *
     * @param b       
     * @return       
     */
    public static BigInteger complementArithmeticValue(int b) {
        Byte[] bytes = asBytes(complementArithmetic(b));
        return BigInteger.valueOf(((b > Integer.MAX_VALUE || b < Integer.MIN_VALUE) ? -1 : 1) * Long.parseLong(Joiner.on("").join((Object[]) bytes), 2));
    }

    /**
     *          
     *
     * @param b       
     * @return       
     */
    public static BigInteger complementArithmeticValue(long b) {
        Byte[] bytes = asBytes(complementArithmetic(b));
        return BigInteger.valueOf(((b > Long.MAX_VALUE || b < Long.MIN_VALUE) ? -1 : 1) * Long.parseLong(Joiner.on("").join((Object[]) bytes), 2));
    }

    /**
     *          
     *
     * @param b       
     * @return       
     */
    public static BigInteger complementArithmeticValue(short b) {
        Byte[] bytes = asBytes(complementArithmetic(b));
        return BigInteger.valueOf(((b > Short.MAX_VALUE || b < Short.MIN_VALUE) ? -1 : 1) * Long.parseLong(Joiner.on("").join((Object[]) bytes), 2));
    }

    /**
     *          
     *
     * @param b       
     * @return       
     */
    public static BigInteger complementArithmeticValue(byte b) {
        Byte[] bytes = asBytes(complementArithmetic(b));
        return BigInteger.valueOf(Long.parseLong(Joiner.on("").join((Object[]) bytes), 2));
    }

    /**
     *             <br />
     *                         0
     *
     * @param bytes              
     * @param offset       
     * @param length             
     * @return                         0
     */
    public static BigDecimal toBigDecimal(byte[] bytes, int offset, int length) {
        byte[] target = new byte[length];
        System.arraycopy(bytes, offset, target, 0, length);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(target);
        buffer.flip();
        if (length == BYTE_SIZE) {
            return BigDecimal.valueOf(target[0]);
        } else if (length == SHORT_SIZE) {
            return BigDecimal.valueOf(buffer.getShort());
        } else if (length == INT_SIZE) {
            return BigDecimal.valueOf(buffer.getFloat());
        } else if (length == LONG_SIZE) {
            return BigDecimal.valueOf(buffer.getDouble());
        }
        return BigDecimal.ZERO;
    }

    /**
     * byte[]      布尔值
     *
     * @param bytes byte array
     * @return boolean
     */
    public static boolean toBoolean(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        byte[] item = new byte[INT_SIZE];
        int length = bytes.length;
        if (length < INT_SIZE) {
            System.arraycopy(bytes, 0, item, INT_SIZE - length, length);
        } else {
            item = bytes;
        }
        int tmp = ByteBuffer.wrap(item, 0, INT_SIZE).getInt();
        return tmp != 0;
    }

    /**
     * byte array 转为 char 值
     *
     * @param bytes byte array
     * @return char 值
     */
    public static char toChar(byte[] bytes) {
        if (null == bytes) {
            throw new IndexOutOfBoundsException();
        }

        return ByteBuffer.wrap(createBytes(CHAR_SIZE, bytes), 0, CHAR_SIZE).getChar();
    }

    /**
     * byte array 转为 char 值
     *
     * @param bytes byte array
     * @return char 值
     */
    public static char[] toChars(byte[] bytes) {
        if (null == bytes || bytes.length < CHAR_SIZE) {
            throw new IndexOutOfBoundsException();
        }
        return ByteBuffer.wrap(bytes).asCharBuffer().array();
    }

    /**
     * byte array 转为 double 值
     *
     * @param bytes byte array
     * @return double 值
     */
    public static double toDouble(byte[] bytes) {
        if (null == bytes) {
            throw new IndexOutOfBoundsException();
        }
        return ByteBuffer.wrap(createBytes(DOUBLE_SIZE, bytes), 0, DOUBLE_SIZE).getDouble();

    }

    /**
     * byte array 转为 float 值
     *
     * @param bytes byte array
     * @return float 值
     */
    public static float toFloat(byte[] bytes) {
        if (null == bytes) {
            throw new IndexOutOfBoundsException();
        }

        return ByteBuffer.wrap(createBytes(FLOAT_SIZE, bytes), 0, FLOAT_SIZE).getFloat();
    }

    /**
     * byte array 转为 int 值
     *
     * @param bytes byte array
     * @return int 值
     */
    public static int toInt(byte[] bytes) {
        if (null == bytes) {
            throw new IndexOutOfBoundsException();
        }

        return ByteBuffer.wrap(createBytes(INT_SIZE, bytes), 0, INT_SIZE).getInt();
    }

    /**
     * byte array 转为 long 值
     *
     * @param bytes byte array
     * @return long 值
     */
    public static long toLong(byte[] bytes) {
        if (null == bytes) {
            throw new IndexOutOfBoundsException();
        }
        return ByteBuffer.wrap(createBytes(LONG_SIZE, bytes), 0, LONG_SIZE).getLong();
    }

    /**
     * byte array 转为 short 值
     *
     * @param bytes byte array
     * @return short 值
     */
    public static short toShort(byte[] bytes) {
        if (null == bytes) {
            throw new IndexOutOfBoundsException();
        }

        return ByteBuffer.wrap(createBytes(SHORT_SIZE, bytes), 0, SHORT_SIZE).getShort();
    }

    /**
     *                            
     *
     * @param bytes             
     * @return          
     */
    public static String toString(byte[] bytes) {
        return new String(bytes, UTF_8);
    }

    /**
     *                            
     *
     * @param bytes             
     * @return          
     * @param charset 字符集
     */
    public static String toString(byte[] bytes, Charset charset) {
        return new String(bytes, charset);
    }

    /**
     *                            
     *
     * @param bytes             
     * @return          
     * @param charset 字符集
     */
    public static String toString(byte[] bytes, String charset) {
        return new String(bytes, Charset.forName(charset));
    }

    /**
     *                   
     *
     * @param size        
     * @param bytes          
     * @return                   
     */
    private static byte[] createBytes(int size, byte[] bytes) {
        byte[] item = new byte[size];
        int length = bytes.length;
        if (length < size) {
            System.arraycopy(bytes, 0, item, size - length, length);
        } else if (length > size) {
            System.arraycopy(bytes, length - size, item, 0, size);
        } else {
            item = bytes;
        }
        return item;
    }

    /**
     *             
     *
     * @param source          
     * @return          
     */
    private static Byte[] asBytes(byte[] source) {
        Byte[] result = new Byte[source.length];
        for (int i = 0; i < source.length; i++) {
            byte b = source[i];
            result[i] = b;
        }
        return result;
    }

    /**
     *                
     *
     * @param str          
     * @return                      
     */
    public static byte[] utf8Bytes(CharSequence str) {
        return bytes(str, UTF_8);
    }

    /**
     *                
     *
     * @param str              
     * @param charset                                                                      
     * @return                      
     */
    public static byte[] bytes(CharSequence str, Charset charset) {
        if (str == null) {
            return null;
        }

        if (null == charset) {
            return str.toString().getBytes();
        }
        return str.toString().getBytes(charset);
    }

    /**
     *                         <br>
     * 1   Byte         byte缓冲                                        2                        Arrays.转为字符串
     *
     * @param obj       
     * @return          
     */
    public static String utf8Str(Object obj) {
        return str(obj, UTF_8);
    }

    /**
     *                         <br>
     * 1   Byte         byte缓冲                                        2                        Arrays.转为字符串
     *
     * @param obj           
     * @param charset          
     * @return          
     */
    public static String str(Object obj, Charset charset) {
        if (null == obj) {
            return null;
        }

        if (obj instanceof String) {
            return (String) obj;
        } else if (obj instanceof byte[]) {
            return str(obj, charset);
        } else if (obj instanceof Byte[]) {
            return str(obj, charset);
        } else if (obj instanceof ByteBuffer) {
            return str(obj, charset);
        } else if (obj.getClass().isArray()) {
            return Joiner.on(",").join((Object[]) obj);
        }

        return obj.toString();
    }


    /**
     *          Hex
     *
     * @param b       
     * @return hex
     */
    public static String asHex(byte b) {
        return Hex.encodeHexString(new byte[]{b});
    }

    /**
     *                Hex
     *
     * @param bytes             
     * @return hex
     */
    public static String asHex(byte[] bytes) {
        return Hex.encodeHexString(bytes);
    }

    /**
     *    int                                          byte                  
     *
     * @param n int
     * @return byte[]
     */
    public static byte[] intToByteBig(int n) {
        byte[] b = new byte[4];
        b[3] = (byte) (n & 0xff);
        b[2] = (byte) (n >> 8 & 0xff);
        b[1] = (byte) (n >> 16 & 0xff);
        b[0] = (byte) (n >> 24 & 0xff);
        return b;
    }

    /**
     *    int                                          byte                  
     *
     * @param n int
     * @return byte[]
     */
    public static byte[] intToByteLittle(int n) {
        byte[] b = new byte[4];
        b[0] = (byte) (n & 0xff);
        b[1] = (byte) (n >> 8 & 0xff);
        b[2] = (byte) (n >> 16 & 0xff);
        b[3] = (byte) (n >> 24 & 0xff);
        return b;
    }

    /**
     * byte         int         (      )
     *
     * @param bytes
     * @return
     */
    public static int bytes2IntLittle(byte[] bytes) {
        int int1 = bytes[0] & 0xff;
        int int2 = (bytes[1] & 0xff) << 8;
        int int3 = (bytes[2] & 0xff) << 16;
        int int4 = (bytes[3] & 0xff) << 24;

        return int1 | int2 | int3 | int4;
    }

    /**
     * byte         int         (      )
     *
     * @param bytes
     * @return
     */
    public static int bytes2IntBig(byte[] bytes) {
        int int1 = bytes[3] & 0xff;
        int int2 = (bytes[2] & 0xff) << 8;
        int int3 = (bytes[1] & 0xff) << 16;
        int int4 = (bytes[0] & 0xff) << 24;

        return int1 | int2 | int3 | int4;
    }

    /**
     *    short                                          byte                  
     *
     * @param n short
     * @return byte[]
     */
    public static byte[] shortToByteBig(short n) {
        byte[] b = new byte[2];
        b[1] = (byte) (n & 0xff);
        b[0] = (byte) (n >> 8 & 0xff);
        return b;
    }

    /**
     *    short                                          byte      (      )
     *
     * @param n short
     * @return byte[]
     */
    public static byte[] shortToByteLittle(short n) {
        byte[] b = new byte[2];
        b[0] = (byte) (n & 0xff);
        b[1] = (byte) (n >> 8 & 0xff);
        return b;
    }

    /**
     *             byte         short
     *
     * @param b
     * @return
     */
    public static short byteToShortLittle(byte[] b) {
        return (short) (((b[1] << 8) | b[0] & 0xff));
    }

    /**
     *             byte         short
     *
     * @param b
     * @return
     */
    public static short byteToShortBig(byte[] b) {
        return (short) (((b[0] << 8) | b[1] & 0xff));
    }

    /**
     * long         byte[] (      )
     *
     * @param n
     * @return
     */
    public static byte[] longToBytesBig(long n) {
        byte[] b = new byte[8];
        b[7] = (byte) (n & 0xff);
        b[6] = (byte) (n >> 8 & 0xff);
        b[5] = (byte) (n >> 16 & 0xff);
        b[4] = (byte) (n >> 24 & 0xff);
        b[3] = (byte) (n >> 32 & 0xff);
        b[2] = (byte) (n >> 40 & 0xff);
        b[1] = (byte) (n >> 48 & 0xff);
        b[0] = (byte) (n >> 56 & 0xff);
        return b;
    }

    /**
     * long         byte[] (      )
     *
     * @param n
     * @return
     */
    public static byte[] longToBytesLittle(long n) {
        byte[] b = new byte[8];
        b[0] = (byte) (n & 0xff);
        b[1] = (byte) (n >> 8 & 0xff);
        b[2] = (byte) (n >> 16 & 0xff);
        b[3] = (byte) (n >> 24 & 0xff);
        b[4] = (byte) (n >> 32 & 0xff);
        b[5] = (byte) (n >> 40 & 0xff);
        b[6] = (byte) (n >> 48 & 0xff);
        b[7] = (byte) (n >> 56 & 0xff);
        return b;
    }

    /**
     * byte[]   long      (      )
     *
     * @param array
     * @return
     */
    public static long bytesToLongLittle(byte[] array) {
        return ((((long) array[0] & 0xff) << 0)
                | (((long) array[1] & 0xff) << 8)
                | (((long) array[2] & 0xff) << 16)
                | (((long) array[3] & 0xff) << 24)
                | (((long) array[4] & 0xff) << 32)
                | (((long) array[5] & 0xff) << 40)
                | (((long) array[6] & 0xff) << 48)
                | (((long) array[7] & 0xff) << 56));
    }

    /**
     * byte[]   long      (      )
     *
     * @param array
     * @return
     */
    public static long bytesToLongBig(byte[] array) {
        return ((((long) array[0] & 0xff) << 56)
                | (((long) array[1] & 0xff) << 48)
                | (((long) array[2] & 0xff) << 40)
                | (((long) array[3] & 0xff) << 32)
                | (((long) array[4] & 0xff) << 24)
                | (((long) array[5] & 0xff) << 16)
                | (((long) array[6] & 0xff) << 8)
                | (((long) array[7] & 0xff) << 0));
    }

}
