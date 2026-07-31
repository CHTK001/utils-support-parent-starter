# utils-support-jdk15on-starter

国密算法支持模块：SM2、SM3、SM4 等基于 BouncyCastle 的加密实现

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-jdk15on-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `BcDesedeCipher` | 基于 BouncyCastle 的 3DES 对称加解密实现 通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者 实现 DESed |
| `BcEciesCipher` | 基于 BouncyCastle 的 ECIES 椭圆曲线集成加密方案实现 通过 SPI 机制以 "bc" 名称注册，实现密钥封装与对称加密结合的混合加密方案。 |
| `BcNoekeonCipher` | 基于 BouncyCastle 的 Noekeon 对称加解密实现 通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者 实现 No |
| `BcRsaCipher` | 基于 BouncyCastle 的 RSA 非对称加解密实现 通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者 实现 RSA 密 |
| `BcSm2Cipher` | 基于 BouncyCastle 的 SM2 非对称加解密实现 通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者 实现 SM2 密 |
| `BcSm4Cipher` | 基于 BouncyCastle 的 SM4 对称加解密实现 通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者 实现 SM4/EC |
| `BcTwofishCipher` | 基于 BouncyCastle 的 Twofish 对称加解密实现 通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者 实现 Tw |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-jdk15on-starter
├── utils-support-common-starter
```