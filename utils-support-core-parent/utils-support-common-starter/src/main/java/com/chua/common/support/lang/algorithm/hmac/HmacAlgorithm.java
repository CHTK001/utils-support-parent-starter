package com.chua.common.support.lang.algorithm.hmac;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
* HMAC (Hash-based Message Authentication Code) 算法枚举。
* <p>
* 该枚举定义了支持的各类 HMAC 哈希算法，包括标准的 MD5、SHA 系列以及国密 SM3 算法。
* 支持通过字符串值快速查找对应的算法实例。
* </p>
*
* @author CH
* @since 2025/10/23
 */
@Getter
public enum HmacAlgorithm {

 /**
 * HMAC-MD5 算法。
 * 使用 MD5 哈希函数生成的消息认证码。
 * 注意：MD5 已不再安全，仅用于遗留系统兼容。
 */
 HMAC_MD5("HmacMD5"),

 /**
 * HMAC-SHA1 算法。
 * 使用 SHA-1 哈希函数生成的消息认证码。
 * 注意：SHA-1 存在碰撞风险，建议在新系统中避免使用。
 */
 HMAC_SHA1("HmacSHA1"),

 /**
 * HMAC-SHA224 算法。
 * 使用 SHA-2 系列中的 SHA-224 哈希函数生成的消息认证码。
 * 输出长度为 224 位。
 */
 HMAC_SHA224("HmacSHA224"),

 /**
 * HMAC-SHA256 算法。
 * 使用 SHA-2 系列中的 SHA-256 哈希函数生成的消息认证码。
 * 目前广泛使用的安全标准算法之一。
 */
 HMAC_SHA256("HmacSHA256"),

 /**
 * HMAC-SHA384 算法。
 * 使用 SHA-2 系列中的 SHA-384 哈希函数生成的消息认证码。
 * 输出长度为 384 位。
 */
 HMAC_SHA384("HmacSHA384"),

 /**
 * HMAC-SHA512 算法。
 * 使用 SHA-2 系列中的 SHA-512 哈希函数生成的消息认证码。
 * 提供更高的安全性，输出长度为 512 位。
 */
 HMAC_SHA512("HmacSHA512"),

 /**
 * HMAC-SHA512/224 算法。
 * 基于 SHA-512 截断至 224 位的变体算法。
 * 需要 JDK 9 或更高版本支持。
 */
 HMAC_SHA512_224("HmacSHA512/224"),

 /**
 * HMAC-SHA512/256 算法。
 * 基于 SHA-512 截断至 256 位的变体算法。
 * 需要 JDK 9 或更高版本支持。
 */
 HMAC_SHA512_256("HmacSHA512/256"),

 /**
 * HMAC-SM3 算法。
 * 使用中国国家密码管理局发布的 SM3 杂凑算法生成的消息认证码。
 * 依赖于 BouncyCastle 库的支持。
 */
 HMAC_SM3("HmacSM3");

 /**
 * 算法在 Java Cryptography Architecture (JCA) 中对应的名称字符串。
 * -- GETTER --
 * 获取当前算法实例在 JCA 中对应的标准名称字符串。
 *
 * @return 算法名称字符串

 */
 private final String value;

 /**
 * 缓存算法名称到枚举实例的映射，用于快速查找。
 */
 private static final Map<String, HmacAlgorithm> ALGORITHM_MAP = Arrays.stream(values())
 .collect(Collectors.toMap(HmacAlgorithm::getValue, a -> a));

 /**
 * 构造函数，初始化算法名称。
 *
 * @param value 算法在 JCA 中的标准名称字符串
 */
 HmacAlgorithm(String value) {
 this.value = value;
 }

 /**
 * 根据给定的算法名称字符串查找对应的枚举实例。
 *
 * @param name 算法名称字符串（例如 "HmacSHA256"）
 * @return 包含对应枚举实例的 Optional，如果未找到则返回空
 */
 public static Optional<HmacAlgorithm> fromValue(String name) {
 if (name == null || name.isEmpty()) {
 return Optional.empty();
 }
 return Optional.ofNullable(ALGORITHM_MAP.get(name));
 }
}

