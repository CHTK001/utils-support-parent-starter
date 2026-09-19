package com.chua.common.support.lang.algorithm.hmac;

import com.chua.common.support.lang.algorithm.crypto.Hex;
import com.chua.common.support.utils.StringUtils;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;


/**
 * HMAC (Hash-based Message Authentication Code) 工具类
 * <p>
 * 提供基于哈希的消息认证码功能，支持多种哈希算法（MD5, SHA1, SHA256, SHA512）
 * 可用于数据完整性校验和消息来源认证。
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 示例 1: 使用字符串密钥计算十六进制摘要
 * HMac hmac = new HMac(HmacAlgorithm.HMAC_SHA256, "mySecretKey");
 * String hex = hmac.digestHex("Hello World");
 * 
 * // 示例 2: 使用字节数组密钥计算原始字节摘要
 * HMac hmac2 = new HMac(HmacAlgorithm.HMAC_SHA256, key.getBytes());
 * byte[] digest = hmac2.digest("Hello World");
 * 
 * // 示例 3: 使用静态工厂方法并获取 Base64 编码的摘要
 * HMac hmac3 = HMac.hmacSha256("myKey");
 * String base64 = hmac3.digestBase64("Hello World");
 * }</pre>
 *
 * @author CH
 * @since 2025/10/23
 */
public class HMac implements Serializable {

 /** 序列化版本号 */
 private static final long serialVersionUID = 1L;

 /**
 * 默认使用的字符集，用于将字符串转换为字节数组
 */
 private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

 /**
  * 处理输入流时使用的缓冲区大小，默认为 8KB
  */
 private static final int BUFFER_SIZE = 8192;

 /**
  * 底层的 Mac 实例，用于执行 HMAC 运算
  * 注意：该实例在构造时初始化，并在同步块中安全使用
  */
 private final Mac mac;

 /**
  * 构造函数：通过算法枚举和字符串密钥创建 HMac 实例
  * <p>
  * 内部会将字符串密钥转换为 UTF-8 字节数组后调用重载方法
  *
  * @param algorithm HMAC 算法类型
  * @param key 用于签名的密钥字符串
  */
 public HMac(HmacAlgorithm algorithm, String key) {
 this(algorithm, StringUtils.utf8Bytes(key));
 }

 /**
  * 构造函数：通过算法枚举和字节数组密钥创建 HMac 实例
  * <p>
  * 内部会提取算法名称字符串并调用更底层的构造函数
  *
  * @param algorithm HMAC 算法类型
  * @param key 用于签名的密钥字节数组
  */
 public HMac(HmacAlgorithm algorithm, byte[] key) {
 this(algorithm.getValue(), key);
 }

 /**
  * 构造函数：通过算法名称字符串和字节数组密钥创建 HMac 实例
  * <p>
  * 内部会创建 SecretKeySpec 对象并调用最终构造函数
  *
  * @param algorithm HMAC 算法名称字符串 (如 "HmacSHA256")
  * @param key 用于签名的密钥字节数组
  */
 public HMac(String algorithm, byte[] key) {
 this(algorithm, new SecretKeySpec(key, algorithm));
 }

 /**
  * 构造函数：通过算法名称字符串和 SecretKey 对象创建 HMac 实例
  * <p>
  * 这是最底层的构造函数，负责初始化 javax.crypto.Mac 实例
  * 如果算法不支持或密钥无效，将抛出 IllegalArgumentException
  *
  * @param algorithm HMAC 算法名称字符串
  * @param key 用于签名的 SecretKey 对象
  */
 public HMac(String algorithm, SecretKey key) {
 try {
 // 获取指定算法的 Mac 实例
 this.mac = Mac.getInstance(algorithm);
 // 使用提供的密钥初始化 Mac 实例
 this.mac.init(key);
 } catch (NoSuchAlgorithmException e) {
 // 当系统不支持指定的 HMAC 算法时抛出异常
 throw new IllegalArgumentException("不支持的 HMAC 算法: " + algorithm, e);
 } catch (InvalidKeyException e) {
 // 当提供的密钥格式不正确或长度不合法时抛出异常
 throw new IllegalArgumentException("无效的密钥", e);
 }
 }

 // ==================== 静态工厂方法 ====================

 /**
  * 快速创建 HMAC-MD5 实例
  *
  * @param key 密钥字符串
  * @return HMac 实例
  */
 public static HMac hmacMd5(String key) {
 return new HMac(HmacAlgorithm.HMAC_MD5, key);
 }

 /**
  * 快速创建 HMAC-MD5 实例
  *
  * @param key 密钥字节数组
  * @return HMac 实例
  */
 public static HMac hmacMd5(byte[] key) {
 return new HMac(HmacAlgorithm.HMAC_MD5, key);
 }

 /**
  * 快速创建 HMAC-SHA1 实例
  *
  * @param key 密钥字符串
  * @return HMac 实例
  */
 public static HMac hmacSha1(String key) {
 return new HMac(HmacAlgorithm.HMAC_SHA1, key);
 }

 /**
  * 快速创建 HMAC-SHA1 实例
  *
  * @param key 密钥字节数组
  * @return HMac 实例
  */
 public static HMac hmacSha1(byte[] key) {
 return new HMac(HmacAlgorithm.HMAC_SHA1, key);
 }

 /**
  * 快速创建 HMAC-SHA256 实例
  *
  * @param key 密钥字符串
  * @return HMac 实例
  */
 public static HMac hmacSha256(String key) {
 return new HMac(HmacAlgorithm.HMAC_SHA256, key);
 }

 /**
  * 快速创建 HMAC-SHA256 实例
  *
  * @param key 密钥字节数组
  * @return HMac 实例
  */
 public static HMac hmacSha256(byte[] key) {
 return new HMac(HmacAlgorithm.HMAC_SHA256, key);
 }

 /**
  * 快速创建 HMAC-SHA512 实例
  *
  * @param key 密钥字符串
  * @return HMac 实例
  */
 public static HMac hmacSha512(String key) {
 return new HMac(HmacAlgorithm.HMAC_SHA512, key);
 }

 /**
  * 快速创建 HMAC-SHA512 实例
  *
  * @param key 密钥字节数组
  * @return HMac 实例
  */
 public static HMac hmacSha512(byte[] key) {
 return new HMac(HmacAlgorithm.HMAC_SHA512, key);
 }

 // ==================== 核心摘要计算方法 ====================

 /**
  * 对字节数组数据进行 HMAC 摘要计算
  * <p>
  * 该方法线程不安全，但在同步块中调用以确保线程安全
  *
  * @param data 待计算的数据
  * @return 计算得到的摘要字节数组
  * @throws IllegalArgumentException 当 data 为 null 时抛出
  */
 public byte[] digest(byte[] data) {
 if (data == null) {
 throw new IllegalArgumentException("输入数据不能为 null");
 }
 synchronized (mac) {
 // 重置 Mac 状态以开始新的计算
 mac.reset();
 return mac.doFinal(data);
 }
 }

 /**
  * 对字符串数据进行 HMAC 摘要计算，默认使用 UTF-8 编码
  *
  * @param data 待计算的字符串数据
  * @return 计算得到的摘要字节数组
  */
 public byte[] digest(String data) {
 return digest(data, DEFAULT_CHARSET);
 }

 /**
  * 对字符串数据进行 HMAC 摘要计算，支持指定字符集
  *
  * @param data 待计算的字符串数据
  * @param charset 用于编码字符串的字符集
  * @return 计算得到的摘要字节数组
  * @throws IllegalArgumentException 当 data 为 null 时抛出
  */
 public byte[] digest(String data, Charset charset) {
 if (data == null) {
 throw new IllegalArgumentException("输入数据不能为 null");
 }
 // 将字符串按指定字符集转换为字节数组后进行计算
 return digest(data.getBytes(charset == null ? DEFAULT_CHARSET : charset));
 }

 /**
  * 对文件内容进行 HMAC 摘要计算
  *
  * @param file 待计算的文件
  * @return 计算得到的摘要字节数组
  * @throws IOException 当文件读取失败时抛出
  * @throws IllegalArgumentException 当文件为 null 或不存在时抛出
  */
 public byte[] digest(File file) throws IOException {
 if (file == null || !file.exists()) {
 throw new IllegalArgumentException("文件不存在或为空");
 }
 // 使用 try-with-resources 确保流自动关闭
 try (InputStream in = new FileInputStream(file)) {
 return digest(in);
 }
 }

 /**
  * 对输入流中的数据进行 HMAC 摘要计算
  * <p>
  * 该方法采用流式处理方式，适合处理大文件或大数据流
  *
  * @param in 待计算的数据输入流
  * @return 计算得到的摘要字节数组
  * @throws IOException 当流读取失败时抛出
  * @throws IllegalArgumentException 当 in 为 null 时抛出
  */
 public byte[] digest(InputStream in) throws IOException {
 if (in == null) {
 throw new IllegalArgumentException("输入流不能为 null");
 }
 synchronized (mac) {
 mac.reset();
 byte[] buffer = new byte[BUFFER_SIZE];
 int read;
 // 循环读取输入流中的数据并更新 Mac 状态
 while ((read = in.read(buffer)) != -1) {
 mac.update(buffer, 0, read);
 }
 // 完成计算并返回最终摘要
 return mac.doFinal();
 }
 }

 // ==================== Hex 编码摘要方法 ====================

 /**
  * 对字节数组数据进行 HMAC 计算并以十六进制字符串形式返回
  *
  * @param data 待计算的数据
  * @return 十六进制格式的摘要字符串
  */
 public String digestHex(byte[] data) {
 return Hex.encodeHexString(digest(data));
 }

 /**
  * 对字符串数据进行 HMAC 计算并以十六进制字符串形式返回，默认使用 UTF-8 编码
  *
  * @param data 待计算的字符串数据
  * @return 十六进制格式的摘要字符串
  */
 public String digestHex(String data) {
 return Hex.encodeHexString(digest(data));
 }

 /**
  * 对字符串数据进行 HMAC 计算并以十六进制字符串形式返回，支持指定字符集
  *
  * @param data 待计算的字符串数据
  * @param charset 用于编码字符串的字符集
  * @return 十六进制格式的摘要字符串
  */
 public String digestHex(String data, Charset charset) {
 return Hex.encodeHexString(digest(data, charset));
 }

 /**
  * 对文件内容进行 HMAC 计算并以十六进制字符串形式返回
  *
  * @param file 待计算的文件
  * @return 十六进制格式的摘要字符串
  * @throws IOException 当文件读取失败时抛出
  */
 public String digestHex(File file) throws IOException {
 return Hex.encodeHexString(digest(file));
 }

 /**
  * 对输入流中的数据进行 HMAC 计算并以十六进制字符串形式返回
  *
  * @param in 待计算的数据输入流
  * @return 十六进制格式的摘要字符串
  * @throws IOException 当流读取失败时抛出
  */
 public String digestHex(InputStream in) throws IOException {
 return Hex.encodeHexString(digest(in));
 }

 // ==================== Base64 编码摘要方法 ====================

 /**
  * 对字节数组数据进行 HMAC 计算并以 Base64 字符串形式返回
  *
  * @param data 待计算的数据
  * @return Base64 编码的摘要字符串
  */
 public String digestBase64(byte[] data) {
 return Base64.getEncoder().encodeToString(digest(data));
 }

 /**
  * 对字符串数据进行 HMAC 计算并以 Base64 字符串形式返回，默认使用 UTF-8 编码
  *
  * @param data 待计算的字符串数据
  * @return Base64 编码的摘要字符串
  */
 public String digestBase64(String data) {
 return Base64.getEncoder().encodeToString(digest(data));
 }

 /**
  * 对字符串数据进行 HMAC 计算并以 Base64 字符串形式返回，支持指定字符集
  *
  * @param data 待计算的字符串数据
  * @param charset 用于编码字符串的字符集
  * @return Base64 编码的摘要字符串
  */
 public String digestBase64(String data, Charset charset) {
 return Base64.getEncoder().encodeToString(digest(data, charset));
 }

 /**
  * 对文件内容进行 HMAC 计算并以 Base64 字符串形式返回
  *
  * @param file 待计算的文件
  * @return Base64 编码的摘要字符串
  * @throws IOException 当文件读取失败时抛出
  */
 public String digestBase64(File file) throws IOException {
 return Base64.getEncoder().encodeToString(digest(file));
 }

 /**
  * 对输入流中的数据进行 HMAC 计算并以 Base64 字符串形式返回
  *
  * @param in 待计算的数据输入流
  * @return Base64 编码的摘要字符串
  * @throws IOException 当流读取失败时抛出
  */
 public String digestBase64(InputStream in) throws IOException {
 return Base64.getEncoder().encodeToString(digest(in));
 }

 // ==================== 验证方法 ====================

 /**
  * 验证给定数据的 HMAC 值是否与预期值匹配
  * <p>
  * 比较时使用忽略大小写的字符串比较
  *
  * @param data 待验证的数据字符串
  * @param expectedHex 预期的十六进制摘要字符串
  * @return 如果匹配返回 true，否则返回 false
  */
 public boolean verify(String data, String expectedHex) {
 String actualHex = digestHex(data);
 return actualHex.equalsIgnoreCase(expectedHex);
 }

 /**
  * 验证给定数据的 HMAC 值是否与预期字节数组匹配
  * <p>
  * 使用常量时间比较防止时序攻击
  *
  * @param data 待验证的数据字节数组
  * @param expectedDigest 预期的摘要字节数组
  * @return 如果匹配返回 true，否则返回 false
  */
 public boolean verify(byte[] data, byte[] expectedDigest) {
 byte[] actualDigest = digest(data);
 // 首先检查长度是否一致
 if (actualDigest.length != expectedDigest.length) {
 return false;
 }
 // 逐字节比较，即使发现不同也继续遍历以防止时序攻击泄露信息
 for (int i = 0; i < actualDigest.length; i++) {
 if (actualDigest[i] != expectedDigest[i]) {
 return false;
 }
 }
 return true;
 }

 // ==================== 工具方法 ====================

 /**
  * 获取当前使用的 HMAC 算法名称
  *
  * @return 算法名称字符串
  */
 public String getAlgorithm() {
 return mac.getAlgorithm();
 }

 /**
  * 获取当前 HMAC 算法生成的摘要长度（字节数）
  *
  * @return 摘要长度
  */
 public int getMacLength() {
 return mac.getMacLength();
 }

 /**
  * 重置 Mac 实例的状态
  * <p>
  * 调用此方法后，可以复用该 HMac 实例进行新的计算，无需重新创建对象
  */
 public void reset() {
 synchronized (mac) {
 mac.reset();
 }
 }

 /**
  * 获取底层的 Mac 实例
  * <p>
  * 注意：直接操作返回的 Mac 实例可能会影响本类的线程安全性
  *
  * @return 底层的 Mac 实例
  */
 public Mac getMac() {
 return mac;
 }
}

