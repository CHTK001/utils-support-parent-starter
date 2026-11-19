# utils-support-jdk15on-starter

> utils 体系的 **BouncyCastle 加解密扩展模块**：为 `utils-support-common-starter` 定义的 `Cipher` SPI 家族提供 8 组基于 BouncyCastle 的算法实现——国密 SM2 / SM4、RSA、ECIES、HPKE（RFC 9180）、Twofish、Noekeon、3DES，全部以 SPI 名 `bc`（注解别名 `bouncycastle`）注册。
>
> 版本：`4.0.0.51`｜JDK：`25`（`java.version=25`，`maven.compiler.release=25`）｜包名：`com.chua.jdk15on.support.crypto`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-jdk15on-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 角色 | common-starter `lang.algorithm.cipher` 下 8 个 SPI 接口的 **BouncyCastle 实现** | 不定义 SPI 接口——接口与 `CipherFlow` / `HpkeFlow` 门面都在 `utils-support-common-starter` |
| 算法 | SM2、SM4（国密）、RSA、ECIES、HPKE（RFC 9180 base 模式）、Twofish、Noekeon、3DES | 不含 AES / DES（走 `jdk` 提供者）；不含 SM3 摘要 |
| 形态 | 纯 SPI 扩展 jar：8 个实现类 + 8 份 `META-INF/extensions` 注册 + GraalVM native-image 配置 | 不是 Spring Boot Starter：无自动配置类、无配置属性、无 Web 端点 |
| 依赖 | 仅 `utils-support-common-starter` + `org.bouncycastle:bcpkix-jdk18on` | 不依赖 Spring、不依赖任何上层业务 starter |

> pom 描述原文：「国密算法支持模块：SM2、SM3、SM4 等基于 BouncyCastle 的加密实现」。**实际源码**中国密部分只有 SM2（非对称）与 SM4（对称）两组实现，SM3 在本模块源码未提供。

### 1.3 模块构成

| 组成 | 数量 | 位置 / 说明 |
| --- | --- | --- |
| Java 实现类 | 8 | `com.chua.jdk15on.support.crypto` 包，全部标注 `@Spi({"bc", "bouncycastle"})` |
| SPI 注册文件 | 8 | `src/main/resources/META-INF/extensions/<接口全限定名>`，每份一行 `bc=<实现类全限定名>` |
| native-image 配置 | 2 | `reflect-config.json`（8 个实现类全量反射注册）+ `resource-config.json`（保留 `META-INF/extensions/.*` 资源） |
| 测试、配置属性、自动配置 | 0 | 源码未提供 |

版本沿革（来自源码 `@since` 标注）：HPKE 相关的 `BcHpkeCipher` 与 common-starter 的 `HpkeCipher` / `HpkeFlow` / `CipherFlow` 为 `4.0.0.42` 引入；其余 7 个算法实现标注 `@since 2026/07/15 ~ 2026/07/16`。

---

## 2. 核心 API 与速查表

业务侧不直接 `new` 实现类，而是经 common-starter 暴露的入口获取本模块实现：

| 需求 | 入口 | 命中的实现与说明 |
| --- | --- | --- |
| SM2 国密非对称加解密 / 签名 | `Sm2Cipher.create("bc")` | `BcSm2Cipher`：sm2p256v1 密钥对，C1C3C2 模式加解密，SM2Signer 签名 / 验签 |
| SM4 国密对称加解密 | `Sm4Cipher.create("bc")` | `BcSm4Cipher`：`SM4/ECB/PKCS7Padding`，密钥必须 16 字节 |
| RSA 加解密 / 签名 | `RsaCipher.create("bc")` | `BcRsaCipher`：`RSA/ECB/PKCS1Padding` + `SHA256withRSA`，密钥 1024/2048/4096 位 |
| HPKE 混合公钥加密 | `HpkeCipher.create("bc")` 或 `HpkeFlow.of()` | `BcHpkeCipher`：X25519 + HKDF-SHA256 + AES-256-GCM，密文 `nonce(12) ‖ 密文 ‖ 标签(16)` |
| ECIES 椭圆曲线集成加密 | `EciesCipher.create("bc")` | `BcEciesCipher`：secp256r1 + ECDH + KDF2/SHA-256 + AES/CBC + HMAC-SHA256（先验签后解密） |
| Twofish 对称加密 | `TwofishCipher.create("bc")` | `BcTwofishCipher`：`Twofish/CBC/PKCS7Padding`，随机 16 字节 IV 前置，密钥 16/24/32 字节 |
| Noekeon 对称加密 | `NoekeonCipher.create("bc")` | `BcNoekeonCipher`：`Noekeon/ECB/PKCS7Padding`，密钥固定 16 字节 |
| 3DES 对称加密 | `DesedeCipher.create("bc")` | `BcDesedeCipher`：`DESede/CBC/PKCS7Padding`，密钥 24 字节，8 字节 IV 前置 |
| 按算法名统一入口 | `CipherFlow.of("sm2").provider("bc").sm2()` | 门面默认提供者即 `bc`；`resolve()` 支持 aes / des / rsa / sm2 / sm4 / ecies / hpke / twofish / noekeon / desede(3des) |

### 2.1 接口方法（本模块实现的方法集）

| 接口 | 方法 |
| --- | --- |
| `Sm2Cipher` | `generateKeyPair()`、`encrypt(publicKey, data)`、`decrypt(privateKey, ciphertext)`、`sign(privateKey, data)`、`verify(publicKey, data, signature)` |
| `RsaCipher` | `generateKeyPair(keySize)` 与默认 2048 位的 `generateKeyPair()`、`encrypt`、`decrypt`、`sign`、`verify` |
| `EciesCipher` | `generateKeyPair()`、`encrypt(publicKey, data)`、`decrypt(privateKey, ciphertext)` |
| `HpkeCipher` | `generateKeyPair()`、`encap(receiverPublicKey, ikm)`、`recoverKey(receiverPrivateKey, enc, ikm)`、`seal(ek, aad, plaintext)`、`open(ek, aad, ciphertext)` |
| `Sm4Cipher`、`TwofishCipher`、`NoekeonCipher`、`DesedeCipher` | `encrypt(key, data)`、`decrypt(key, ciphertext)`；接口层另有 `encryptToString` / `decryptToString`（Base64 便捷 default 方法） |

### 2.2 关键实现约束（源自码）

- **BC 提供者注册**：所有实现类的 `static` 块在 `Security.getProvider("BC") == null` 时执行 `Security.addProvider(new BouncyCastleProvider())`。
- **SM4 密钥校验**：`BcSm4Cipher` 要求密钥恰为 16 字节，否则抛 `IllegalArgumentException("SM4 密钥长度必须为 16 字节（128 位）")`。
- **Twofish / 3DES 的 IV**：加密时随机生成 IV 并**前置在密文头部**（Twofish 16 字节、3DES 8 字节），解密时先取头部 IV 再解余下密文；密文长度不足（缺 IV 头）直接抛 `IllegalArgumentException`。
- **Noekeon 填充选择**：源码注释明确不用 `ZeroBytePadding`——该填充无法区分明文尾部 `0x00` 与填充字节，解密会把明文尾部的零当填充删掉造成静默截断，故采用 PKCS7Padding。
- **HPKE nonce**：每次 `seal` 现取 12 字节随机 nonce 放入密文头；nonce 不得由密钥确定性派生，否则 (key, nonce) 复用会使 GCM 同时失去机密性与认证。HKDF 盐 / info 为固定域分隔值（`chua-hpke-salt-v1` / `chua-hpke-key`）。`BcHpkeCipher` 另提供 `gcmTagLength()`（返回 16）。
- **ECIES 认证**：密文输出必须带 HMAC-SHA256 认证标签（encrypt-then-MAC），解密先校验标签再解密，防 padding oracle 与位翻转篡改；信封各段长度前缀为 4 字节大端。

---

## 3. 快速开始

### 3.1 SM4：最短可运行示例

```java
import com.chua.common.support.lang.algorithm.cipher.Sm4Cipher;

import java.nio.charset.StandardCharsets;

public class Sm4Demo {
    public static void main(String[] args) {
        Sm4Cipher sm4 = Sm4Cipher.create("bc");                            // SPI 命中 BcSm4Cipher
        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);  // 必须 16 字节

        byte[] ciphertext = sm4.encrypt(key, "机密数据".getBytes(StandardCharsets.UTF_8));
        byte[] plaintext  = sm4.decrypt(key, ciphertext);

        String enc64 = sm4.encryptToString(key, "机密数据");                // Base64 便捷模式
        String dec   = sm4.decryptToString(key, enc64);
    }
}
```

### 3.2 SM2：密钥生成 + 加解密 + 签名验签

```java
import com.chua.common.support.lang.algorithm.cipher.Sm2Cipher;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

Sm2Cipher sm2 = Sm2Cipher.create("bc");
KeyPair keyPair   = sm2.generateKeyPair();
byte[] publicKey  = keyPair.getPublic().getEncoded();
byte[] privateKey = keyPair.getPrivate().getEncoded();

byte[] ciphertext = sm2.encrypt(publicKey, "机密数据".getBytes(StandardCharsets.UTF_8));
byte[] plaintext  = sm2.decrypt(privateKey, ciphertext);

byte[] signature = sm2.sign(privateKey, plaintext);
boolean valid    = sm2.verify(publicKey, plaintext, signature);
```

### 3.3 HPKE：链式门面（KEM + AEAD，适合加密大报文）

```java
import com.chua.common.support.lang.algorithm.cipher.HpkeFlow;

byte[][] keys = HpkeFlow.of().keys();                 // [0]=公钥, [1]=私钥（各 32 字节）
byte[] aad = "order-1024".getBytes();

// 发送方：KEM 封装 + AEAD 加密
HpkeFlow.SealedMessage msg = HpkeFlow.of()
        .receiverPk(keys[0])
        .aad(aad)
        .seal("机密数据".getBytes());

// 接收方：KEM 开包 + AEAD 解密（enc 必须来自发送方 msg.enc()）
byte[] plaintext = HpkeFlow.of()
        .secretKey(keys[1])
        .enc(msg.enc())
        .aad(aad)
        .open(msg.ciphertext());
```

### 3.4 统一门面 CipherFlow

```java
import com.chua.common.support.lang.algorithm.cipher.CipherFlow;

byte[] ct    = CipherFlow.of("sm2").provider("bc").sm2().encrypt(publicKey, data);
byte[] rsaCt = CipherFlow.of("rsa").rsa().encrypt(publicKey, data);   // 默认提供者即 bc
```

> `CipherFlow.of(...)` 支持的算法名：`aes`、`des`（两者固定走 JDK 内置实现）、`rsa`、`sm2`、`sm4`、`ecies`、`hpke`、`twofish`、`noekeon`、`desede` / `3des`。

---

## 4. 配置项

**无。** 依据：

1. 本模块 `pom.xml` 仅声明 `utils-support-common-starter` 与 `org.bouncycastle:bcpkix-jdk18on` 两个依赖，无 Spring / 配置处理相关依赖；
2. `src/main/resources` 下只有 `META-INF/extensions/*`（8 份 SPI 注册）与 `META-INF/native-image/*`（reflect / resource 配置），没有任何 properties / yaml 配置文件；
3. 源码中没有 `@ConfigurationProperties`、环境变量读取或 `application.*` 配置键。

唯一需要调用方「选择」的是 **SPI 提供者名称**：`bc` 或 `bouncycastle`（见 `@Spi({"bc", "bouncycastle"})`），在 `create("bc")`、`CipherFlow.provider("bc")`、`HpkeFlow.of("bc")` 处传入。

---

## 5. SPI 扩展点

本模块是 SPI 的**实现方**，不定义扩展点。它向 common-starter 的下列接口各注册一份实现，注册文件位于 `src/main/resources/META-INF/extensions/`，key 均为 `bc`：

| SPI 接口（`com.chua.common.support.lang.algorithm.cipher`） | 本模块实现 |
| --- | --- |
| `Sm2Cipher` | `com.chua.jdk15on.support.crypto.BcSm2Cipher` |
| `Sm4Cipher` | `com.chua.jdk15on.support.crypto.BcSm4Cipher` |
| `RsaCipher` | `com.chua.jdk15on.support.crypto.BcRsaCipher` |
| `HpkeCipher` | `com.chua.jdk15on.support.crypto.BcHpkeCipher` |
| `EciesCipher` | `com.chua.jdk15on.support.crypto.BcEciesCipher` |
| `TwofishCipher` | `com.chua.jdk15on.support.crypto.BcTwofishCipher` |
| `NoekeonCipher` | `com.chua.jdk15on.support.crypto.BcNoekeonCipher` |
| `DesedeCipher` | `com.chua.jdk15on.support.crypto.BcDesedeCipher` |

注册文件内容（以 SM2 为例，8 份文件同构）：

```text
bc=com.chua.jdk15on.support.crypto.BcSm2Cipher
```

- 各接口的 `create(provider)` 内部调用 `ServiceProvider.of(XxxCipher.class).getNewExtension(provider)`，**每次调用新建实例**；
- `@Spi` 注解同时声明 `bc` 与 `bouncycastle` 两个名称，注册文件以 `bc` 为准；
- GraalVM native-image 下：`reflect-config.json` 为 8 个实现类开启全量构造器 / 方法 / 字段反射，`resource-config.json` 保留 `META-INF/extensions/.*`，保证 SPI 注册文件在 native 镜像中可被读取。

---

## 6. 构建与依赖

### 6.1 依赖（pom.xml 实际声明）

| 依赖 | 版本 | 作用 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `4.0.0.51`（`${project.version}`） | 提供 8 个 SPI 接口与 `CipherFlow` / `HpkeFlow` 门面 |
| `org.bouncycastle:bcpkix-jdk18on` | `1.86`（根 pom `dependencyManagement` 统一管理） | BouncyCastle 运行时；实现中使用的 `org.bouncycastle.crypto.*` / `org.bouncycastle.jce.provider.*` 原语 |

### 6.2 编译

```bash
cd utils-support-parent-starter
mvn -o -q compile -DskipTests
```

- JDK 要求：`java.version=25`、`maven.compiler.release=25`（根 pom 注释明确源码使用 record、箭头 switch 等语法）；
- 本模块隶属聚合模块 `utils-support-extra-parent`（描述：「扩展模块父模块：ASM、AST、验证码等」），随仓库整体构建。

---

## 7. 边界：这些能力不在本模块

| 能力 | 位置 / 说明 |
| --- | --- |
| 8 个 SPI 接口、`Cipher` 顶级接口、`CipherFlow` / `HpkeFlow` 门面 | `utils-support-common-starter` 的 `com.chua.common.support.lang.algorithm.cipher` 包 |
| AES、DES 算法实现 | 本模块未提供；`CipherFlow.aes()` / `des()` 固定走 `jdk` 提供者（`AesCipher` / `DesCipher`） |
| SM3 摘要 | 本模块源码未提供；摘要能力见 `utils-support-common-starter` 的 `DigestUtils`（含 `sm3`） |
| 证书 / TLS / X.509 管理 | 源码未提供；本模块虽声明 `bcpkix-jdk18on` 依赖，但 8 个实现类均只使用 BC 的 crypto / jce 原语 |
| Spring 自动配置、配置属性、监控端点 | 源码未提供；本模块是纯 SPI 扩展 jar，不是 Spring Boot Starter |
| 密钥存储、密钥轮换、KMS 对接 | 源码未提供 |
