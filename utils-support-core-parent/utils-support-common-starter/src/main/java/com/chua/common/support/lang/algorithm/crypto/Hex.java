package com.chua.common.support.lang.algorithm.crypto;

import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;


/**
 * 十六进制编码与解码工具类。
 * <p>
 * 提供字节数组、字符串和 ByteBuffer 之间的十六进制转换功能，
 * 参考 Apache Commons Codec Hex 实现风格。
 *
 * @author CH
 * @version 1.0.0
 * @since 2025/11/29
 */
@Getter
public class Hex {

 /**
  * 十六进制前缀小写形式 (例如："0x")
  */
 public static final String PREFIX = "0x";

 /**
  * 十六进制前缀大写形式 (例如："0X")
  */
 public static final String PREFIX_UPPER = "0X";

 /**
  * 用于十六进制编码的小写字母字符集 ('0'-'9', 'a'-'f')
  */
 private static final char[] DIGITS_LOWER = {
 '0', '1', '2', '3', '4', '5', '6', '7',
 '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
 };

 /**
  * 用于十六进制编码的大写字母字符集 ('0'-'9', 'A'-'F')
  */
 private static final char[] DIGITS_UPPER = {
 '0', '1', '2', '3', '4', '5', '6', '7',
 '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
 };

 /**
  * 当前实例使用的字符集，用于字符串与字节的转换
  * -- GETTER --
  * 获取当前实例使用的字符集
  *
  * @return 字符集对象

  */
 private final Charset charset;

 /**
  * 默认构造函数，使用 UTF-8 字符集
  */
 public Hex() {
 this(StandardCharsets.UTF_8);
 }

 /**
  * 构造方法，指定字符集
  *
  * @param charset 指定的字符集，如果为 null 则默认使用 UTF-8
  */
 public Hex(Charset charset) {
 this.charset = charset != null ? charset : StandardCharsets.UTF_8;
 }

 /**
  * 构造方法，通过字符集名称创建实例
  *
  * @param charsetName 字符集名称 (如 "UTF-8", "GBK")
  */
 public Hex(String charsetName) {
 this(Charset.forName(charsetName));
 }

 /**
  * 将字节数组转换为十六进制字符数组
  * 默认使用小写字母
  *
  * @param data 待编码的字节数组
  * @return 十六进制表示的字符数组
  */
 public static char[] encodeHex(byte[] data) {
 return encodeHex(data, true);
 }

 /**
  * 将字节数组转换为十六进制字符数组，可指定大小写
  *
  * @param data 待编码的字节数组
  * @param toLowerCase 如果为 true 则使用小写字母，否则使用大写字母
  * @return 十六进制表示的字符数组
  */
 public static char[] encodeHex(byte[] data, boolean toLowerCase) {
 return encodeHex(data, toLowerCase ? DIGITS_LOWER : DIGITS_UPPER);
 }

 /**
  * 内部方法：将字节数组转换为十六进制字符数组
  *
  * @param data 待编码的字节数组
  * @param toDigits 用于映射数字的字符数组 (小写或大写)
  * @return 十六进制表示的字符数组
  */
 private static char[] encodeHex(byte[] data, char[] toDigits) {
 if (data == null) {
 return new char[0];
 }
 int length = data.length;
 // 每个字节转换为两个十六进制字符
 char[] out = new char[length << 1];
 for (int i = 0, j = 0; i < length; i++) {
 // 取出高4位并映射
 out[j++] = toDigits[(0xF0 & data[i]) >>> 4];
 // 取出低4位并映射
 out[j++] = toDigits[0x0F & data[i]];
 }
 return out;
 }

 /**
  * 将字节数组转换为十六进制字符串
  * 默认使用小写字母
  *
  * @param data 待编码的字节数组
  * @return 十六进制字符串
  */
 public static String encodeHexString(byte[] data) {
 return new String(encodeHex(data));
 }

 /**
  * 将字节数组转换为十六进制字符串，可指定大小写
  *
  * @param data 待编码的字节数组
  * @param toLowerCase 如果为 true 则使用小写字母，否则使用大写字母
  * @return 十六进制字符串
  */
 public static String encodeHexString(byte[] data, boolean toLowerCase) {
 return new String(encodeHex(data, toLowerCase));
 }

 /**
  * 将 ByteBuffer 转换为十六进制字符数组
  *
  * @param data 待编码的 ByteBuffer
  * @return 十六进制表示的字符数组
  */
 public static char[] encodeHex(ByteBuffer data) {
 return encodeHex(toByteArray(data));
 }

 /**
  * 将 ByteBuffer 转换为十六进制字符串
  *
  * @param data 待编码的 ByteBuffer
  * @return 十六进制字符串
  */
 public static String encodeHexString(ByteBuffer data) {
 return new String(encodeHex(data));
 }

 /**
  * 将十六进制字符数组解码为字节数组
  *
  * @param data 十六进制字符数组
  * @return 解码后的字节数组
  * @throws IllegalArgumentException 当字符数为奇数或包含非法十六进制字符时抛出
  */
 public static byte[] decodeHex(char[] data) {
 if (data == null) {
 return new byte[0];
 }
 int length = data.length;
 // 十六进制字符串长度必须是偶数
 if ((length & 0x01) != 0) {
 throw new IllegalArgumentException("Odd number of characters.");
 }
 byte[] out = new byte[length >> 1];
 for (int i = 0, j = 0; j < length; i++) {
 // 解析高位字符
 int f = toDigit(data[j], j) << 4;
 j++;
 // 解析低位字符并与高位合并
 f = f | toDigit(data[j], j);
 j++;
 out[i] = (byte) (f & 0xFF);
 }
 return out;
 }

 /**
  * 将十六进制字符串解码为字节数组
  *
  * @param data 十六进制字符串
  * @return 解码后的字节数组
  * @throws IllegalArgumentException 当字符串长度为奇数或包含非法十六进制字符时抛出
  */
 public static byte[] decodeHex(String data) {
 if (data == null) {
 return new byte[0];
 }
 return decodeHex(data.toCharArray());
 }

 /**
  * 将单个十六进制字符转换为对应的数值
  *
  * @param ch 十六进制字符
  * @param index 字符在原始数据中的索引位置 (用于错误提示)
  * @return 对应的数值 (0-15)
  * @throws IllegalArgumentException 当字符不是有效的十六进制字符时抛出
  */
 private static int toDigit(char ch, int index) {
 int digit = Character.digit(ch, 16);
 if (digit == -1) {
 throw new IllegalArgumentException("Illegal hexadecimal character " + ch + " at index " + index);
 }
 return digit;
 }

 /**
  * 将 ByteBuffer 转换为字节数组
  *
  * @param byteBuffer 输入的 ByteBuffer
  * @return 转换后的字节数组
  */
 private static byte[] toByteArray(ByteBuffer byteBuffer) {
 if (byteBuffer == null) {
 return new byte[0];
 }
 int remaining = byteBuffer.remaining();
 // 如果 ByteBuffer 内部有数组且剩余量等于数组长度，直接返回数组副本
 if (byteBuffer.hasArray()) {
 byte[] byteArray = byteBuffer.array();
 if (remaining == byteArray.length) {
 byteBuffer.position(remaining);
 return byteArray;
 }
 }
 // 否则创建新数组并读取数据
 byte[] byteArray = new byte[remaining];
 byteBuffer.get(byteArray);
 return byteArray;
 }

 /**
  * 获取当前实例使用的字符集名称
  *
  * @return 字符集名称字符串
  */
 public String getCharsetName() {
 return charset.name();
 }

 /**
  * 将字节数组编码为十六进制字符串，再转换为字节数组 (基于当前字符集)
  *
  * @param source 源字节数组
  * @return 编码后的字节数组 (十六进制字符串的字节表示)
  */
 public byte[] encode(byte[] source) {
 return encodeHexString(source).getBytes(charset);
 }

 /**
  * 将字节数组 (十六进制字符串) 解码为原始字节数组 (基于当前字符集)
  *
  * @param source 源字节数组 (包含十六进制字符串的字节)
  * @return 解码后的原始字节数组
  */
 public byte[] decode(byte[] source) {
 return decodeHex(new String(source, charset));
 }

 /**
  * 将字符串编码为十六进制字符串 (基于当前字符集)
  *
  * @param source 源字符串
  * @return 十六进制字符串
  */
 public String encode(String source) {
 return encodeHexString(source.getBytes(charset));
 }

 /**
  * 将十六进制字符串解码为原始字符串 (基于当前字符集)
  *
  * @param source 十六进制字符串
  * @return 解码后的原始字符串
  */
 public String decode(String source) {
 return new String(decodeHex(source), charset);
 }

 /**
  * 将单个字节追加到 StringBuilder 中作为十六进制字符串
  *
  * @param builder 目标 StringBuilder
  * @param b 待转换的字节
  * @param toLowerCase 是否使用小写字母
  */
 public static void appendHex(StringBuilder builder, byte b, boolean toLowerCase) {
 final char[] toDigits = toLowerCase ? DIGITS_LOWER : DIGITS_UPPER;
 // 提取高4位
 int high = (b & 0xf0) >>> 4;
 // 提取低4位
 int low = b & 0x0f;
 builder.append(toDigits[high]);
 builder.append(toDigits[low]);
 }

}
