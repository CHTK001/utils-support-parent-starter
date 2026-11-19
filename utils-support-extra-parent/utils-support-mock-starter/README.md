# utils-support-mock-starter

> utils 体系的 **Mock 测试数据生成模块**：基于 `MockString` SPI 生成姓名、手机号、邮箱、地址等字符串数据，内置 63 个生成器，对外提供统一门面 `MockStringFactory`。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`，由根 POM 统一管理）｜包名：`com.chua.mock.support`（实现位于 `com.chua.mock.support.provider`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. MockStringFactory 核心 API](#2-mockstringfactory-核心-api)
- [3. 常用能力速查](#3-常用能力速查)
- [4. 全量生成器清单](#4-全量生成器清单)
- [5. 快速开始](#5-快速开始)
- [6. MockEnvironment 注入式上下文](#6-mockenvironment-注入式上下文)
- [7. 配置项](#7-配置项)
- [8. SPI 扩展点](#8-spi-扩展点)
- [9. 构建与依赖](#9-构建与依赖)
- [10. 边界：这些能力不在本模块](#10-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mock-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 字符串型测试数据生成器集合：姓名、手机号、邮箱、身份证、地址、图片 URL 等 63 个 `MockString` SPI 实现 | 不是对象级 / 方法级 mock 框架（无打桩、无动态代理） |
| 入口 | 统一门面 `MockStringFactory`（按名称生成），或 `MockString` SPI 直接注入 | 不提供 Spring Boot 自动装配，无配置属性文件 |
| 依赖 | 运行时仅依赖 `utils-support-common-starter`；编译期依赖 `utils-support-ast-starter` 生成 SPI 索引 | 不依赖数据库驱动、网络 SDK 等运行时第三方服务（图片生成器只拼接 URL，不请求网络） |

### 1.3 规模与构成

- 门面：`com.chua.mock.support.MockStringFactory`（1 个）。
- 生成器：`com.chua.mock.support.provider` 下 **63 个** `MockString` 实现。
- 内部工具：`CnIdCardUtils`（中国身份证号生成逻辑，供 `IdCardMockString`、`DriverLicenseMockString` 等复用，本身不是 SPI 实现）。
- SPI 契约 `com.chua.common.support.mock.MockString` 与上下文 `com.chua.common.support.mock.MockEnvironment` 定义在 `utils-support-common-starter`，本模块是契约的实现方。
- `META-INF/extensions/com.chua.common.support.mock.MockString` SPI 索引由 `utils-support-ast-starter` 的 `AutoSpiAstProcessor` 在编译期自动生成，本模块源码树不手写该文件。
- 内置 GraalVM `native-image` 的 `reflect-config.json` / `proxy-config.json` / `resource-config.json`。
- 门面与全部生成器均为 `@since 4.0.0.42`。

---

## 2. MockStringFactory 核心 API

`com.chua.mock.support.MockStringFactory` 是基于 `MockString` SPI 的静态门面，按名称获取生成器并生成字符串数据。名称匹配**大小写不敏感**；未注册的名称返回 `null`（不回退到默认实现），便于调用方识别拼写错误。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `getMockString(String name)` | `MockString`（可空） | 按名称获取生成器实例；未注册返回 `null` |
| `isSupport(String name)` | `boolean` | 判断指定名称的生成器是否存在 |
| `names()` | `List<String>` | 全部已注册的生成器名称集合 |
| `generate(String name)` | `String`（可空） | 使用默认 Mock 环境生成字符串；生成器不存在返回 `null` |
| `generate(String name, MockEnvironment environment)` | `String`（可空） | 注入 Mock 环境生成；生成器或环境为空返回 `null` |
| `generate(String name, int length)` | `String`（可空） | 按固定长度环境生成（等价于 `MockEnvironment.of(length)`） |
| `generate(String name, String keyword)` | `String`（可空） | 按关键词生成（如按主题生成图片 URL）；关键词为空返回 `null` |
| `generate(String name, String keyword, int length)` | `String`（可空） | 按关键词与长度生成 |
| `generateList(String name, int count)` | `List<String>` | 批量生成；生成器不存在或 `count < 1` 返回空列表 |
| `generateList(String name, int count, MockEnvironment environment)` | `List<String>` | 注入环境批量生成；同上返回空列表 |

> **长度语义由各生成器自行解释**：如 `random` / `password` 按环境长度生成；`email` 的用户名长度固定在 `[5, 12]` 区间，不随环境长度变化；`image` 在长度落在 `[10, 2048]` 时将其作为正方形图片边长。

---

## 3. 常用能力速查

| 需求 | 调用 | 说明 |
| --- | --- | --- |
| 随机中文姓名 | `MockStringFactory.generate("name")` | 百家姓姓氏池 + 常用名用字池，生成 2-4 字姓名（`NameMockString`） |
| 随机手机号 | `MockStringFactory.generate("phone")` | 中国大陆 11 位手机号 `1[3-9]xxxxxxxxx`，不保证号码真实可用（`PhoneMockString`） |
| 随机邮箱 | `MockStringFactory.generate("email")` | 5-12 位随机用户名 + 常用域名池（qq.com、163.com、gmail.com 等）（`EmailMockString`） |
| 身份证号 | `MockStringFactory.generate("idcard")` | 按 GB 11643-1999 生成 18 位身份证号，mod 11-2 校验码，出生日期 1960-01-01 ~ 2005-12-31（`IdCardMockString`） |
| 中国地址 | `MockStringFactory.generate("address")` | 省份 + 下辖城市（真实匹配）+ 街道 + 门牌号（`AddressMockString`） |
| 强密码 | `MockStringFactory.generate("password")` | 大小写字母、数字、特殊符号至少各一，默认长度区间 `[8, 20]`（`PasswordMockString`） |
| 图片 URL | `MockStringFactory.generate("image")` | picsum.photos 随机图（固定 seed 可复现）；带关键词时走 loremflickr.com 按主题取图；返回 URL 而非图片内容（`ImageMockString`） |
| UUID | `MockStringFactory.generate("uuid")` | 标准 32 位十六进制 UUID 字符串（带连字符）（`UuidMockString`） |
| 随机字符串 | `MockStringFactory.generate("random", 16)` | 大小写字母 + 数字随机串，按环境长度生成；本 SPI 的默认实现（`RandomMockString`，`@SpiDefault`） |

批量与可复现：

```java
// 批量 10 个手机号
List<String> phones = MockStringFactory.generateList("phone", 10);

// 固定随机种子，结果可复现
String s = MockStringFactory.generate("random", MockEnvironment.of(42L));

// 按关键词生成主题图片 URL
String img = MockStringFactory.generate("image", "cat", 800);
```

---

## 4. 全量生成器清单

63 个生成器均位于 `com.chua.mock.support.provider`，通过 `@Spi` 声明名称（大小写不敏感），下表为「SPI 名称（含别名）→ 实现类」的完整映射。

| SPI 名称（含别名） | 实现类 |
| --- | --- |
| `address` | `AddressMockString` |
| `amount` / `money` / `price` | `AmountMockString` |
| `bankcard` / `bank-card` / `card-no` / `cardno` | `BankCardMockString` |
| `base64` / `base64-encode` | `Base64MockString` |
| `captcha` / `verify-code` / `verification-code` | `CaptchaMockString` |
| `plate` / `car-plate` / `license-plate` | `CarPlateMockString` |
| `chinese` / `cn` / `hanzi` | `ChineseMockString` |
| `city` | `CityMockString` |
| `color` / `colour` / `hex-color` | `ColorMockString` |
| `company` | `CompanyMockString` |
| `constellation` | `ConstellationMockString` |
| `coordinate` / `latlng` / `lnglat` / `gps` | `CoordinateMockString` |
| `cvv` / `cvv2` / `security-code` | `CvvMockString` |
| `date` | `DateMockString` |
| `datetime` / `timestamp` | `DateTimeMockString` |
| `digit` / `digits` / `number` | `DigitMockString` |
| `domain` / `domain-name` | `DomainMockString` |
| `driver-license` / `driver-license-no` | `DriverLicenseMockString` |
| `email` | `EmailMockString` |
| `en-name` / `english-name` | `EnglishNameMockString` |
| `file-ext` / `file-extension` / `extension` | `FileExtMockString` |
| `gender` | `GenderMockString` |
| `idcard` / `id-card` / `identity-card` / `sfz` | `IdCardMockString` |
| `image` / `image-url` / `img` / `img-url` / `picture` | `ImageMockString` |
| `invoice` / `invoice-no` / `invoice-number` | `InvoiceMockString` |
| `ip` | `IpMockString` |
| `ipv6` / `ip-v6` | `Ipv6MockString` |
| `job` / `occupation` / `career` | `JobMockString` |
| `letter` / `letters` | `LetterMockString` |
| `lorem` / `lorem-ipsum` / `lipsum` | `LoremMockString` |
| `mac` / `mac-address` | `MacMockString` |
| `md5` / `md5-hex` / `digest` | `Md5MockString` |
| `name` / `cn-name` / `chinese-name` | `NameMockString` |
| `nickname` | `NicknameMockString` |
| `order-no` / `order-id` / `order` | `OrderNoMockString` |
| `org-code` / `organization-code` / `orgcode` | `OrgCodeMockString` |
| `passport` / `passport-no` / `passport-number` | `PassportMockString` |
| `password` | `PasswordMockString` |
| `phone` / `mobile` / `cellphone` | `PhoneMockString` |
| `pinyin` / `name-pinyin` | `PinyinMockString` |
| `port` / `tcp-port` | `PortMockString` |
| `postcode` / `zip` / `zipcode` | `PostcodeMockString` |
| `province` | `ProvinceMockString` |
| `qq` / `qq-no` / `qq-number` | `QqMockString` |
| `random` / `string`（默认实现，`order = -100`） | `RandomMockString` |
| `school` / `school-name` | `SchoolMockString` |
| `school-stage` / `stage` / `education-stage` / `education-level` | `SchoolStageMockString` |
| `sentence` / `cn-sentence` | `SentenceMockString` |
| `serial-no` / `serial` / `transaction-no` / `flow-no` | `SerialNoMockString` |
| `short-id` / `shortid` / `short-code` | `ShortIdMockString` |
| `snowflake` / `snowflake-id` | `SnowflakeMockString` |
| `stock-code` / `stock` | `StockCodeMockString` |
| `street` | `StreetMockString` |
| `telephone` / `tel` / `landline` / `phone-landline` | `TelephoneMockString` |
| `tracking-no` / `express-no` / `waybill-no` / `courier-no` | `TrackingNoMockString` |
| `url` / `web-url` / `link` | `UrlMockString` |
| `uscc` / `credit-code` / `social-credit-code` | `UsccMockString` |
| `username` | `UsernameMockString` |
| `uuid` | `UuidMockString` |
| `version` / `version-no` / `semver` | `VersionMockString` |
| `vin` / `car-vin` | `VinMockString` |
| `wechat` / `wechat-id` / `wx` | `WechatMockString` |
| `zodiac` | `ZodiacMockString` |

运行期可用 `MockStringFactory.names()` 动态获取全部已注册名称。

---

## 5. 快速开始

最短可运行示例（以下调用均来自 `MockStringFactory` 源码注释中的使用示例）：

```java
import com.chua.common.support.mock.MockEnvironment;
import com.chua.mock.support.MockStringFactory;

MockStringFactory.generate("name");                 // 随机中文姓名
MockStringFactory.generate("phone");                // 随机手机号
MockStringFactory.generate("email", 12);            // 指定长度
MockStringFactory.generate("random", MockEnvironment.of(8, 16)); // 注入环境（长度区间 8~16）
```

带依赖的完整用法：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mock-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

```java
import com.chua.common.support.mock.MockEnvironment;
import com.chua.mock.support.MockStringFactory;
import java.util.List;

String name      = MockStringFactory.generate("name");       // 如「张伟」
String phone     = MockStringFactory.generate("phone");      // 如 13812345678
List<String> ids = MockStringFactory.generateList("idcard", 5); // 批量 5 个身份证号

// 可复现：固定随机种子
MockEnvironment env = MockEnvironment.of(42L);
String a = MockStringFactory.generate("username", env);
String b = MockStringFactory.generate("username", env);      // 同种子同序列
```

也可以不走门面，直接使用 SPI 注入方式获取实现（`MockString` 接口注释给出的用法）：

```java
@Spi("name")
private MockString nameMock;

String name = nameMock.getString(MockEnvironment.of());
```

---

## 6. MockEnvironment 注入式上下文

`com.chua.common.support.mock.MockEnvironment`（定义在 `utils-support-common-starter`）是 `MockString` 的注入式上下文，统一传递随机数源、长度区间、地区、字符集与关键词，保证生成结果**可复现、可控**。

工厂方法：

| 工厂方法 | 说明 |
| --- | --- |
| `MockEnvironment.of()` | 默认环境 |
| `MockEnvironment.of(int length)` | 固定长度（下限 = 上限 = length） |
| `MockEnvironment.of(int minLength, int maxLength)` | 长度区间 |
| `MockEnvironment.of(long seed)` | 固定随机种子（可复现） |
| `MockEnvironment.of(Locale locale)` | 指定地区（默认 `Locale.CHINA`） |
| `MockEnvironment.ofKeyword(String keyword)` | 带关键词（默认长度） |
| `MockEnvironment.ofKeyword(String keyword, int length)` | 带关键词与长度 |

生成器可消费的便捷方法：`random()`（未设置种子时懒加载 `ThreadLocalRandom`）、`nextInt(bound)`、`nextInt(origin, bound)`、`nextLong(bound)`、`nextLong(origin, bound)`、`length()`（区间内随机长度）、`randomOf(T[] array)`（随机选一）。

默认常量：`DEFAULT_MIN_LENGTH = 1`、`DEFAULT_MAX_LENGTH = 64`、`DEFAULT_CHARSET = "UTF-8"`；可读写属性：`minLength`、`maxLength`、`locale`、`charset`、`keyword`、`random`（`setSeed(long)` 可固定种子）。

---

## 7. 配置项

**无。**

依据：本模块 `src/main/resources` 仅包含 GraalVM `native-image` 配置（`reflect-config.json` / `proxy-config.json` / `resource-config.json`），没有 `spring.factories`、`AutoConfiguration.imports` 或任何 `@ConfigurationProperties` 属性文件；模块 POM 也未引入 Spring Boot 自动装配依赖。生成行为不通过配置文件控制，而是通过程序化注入 `MockEnvironment`（随机种子、长度区间、地区、关键词等，见第 6 节）控制。

---

## 8. SPI 扩展点

扩展点为 `utils-support-common-starter` 中的接口 **`com.chua.common.support.mock.MockString`**，只有一个方法：

```java
@Nonnull
String getString(@Nonnull MockEnvironment environment);
```

（另有 `default String getString()`，等价于 `getString(MockEnvironment.of())`。）

新增自定义生成器的步骤（与本模块内置生成器的写法一致）：

```java
package com.example;

import com.chua.ast.support.annotation.AutoSpi;
import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;

import javax.annotation.Nonnull;

@Spi("my-generator")                                       // 生成器名称（可多别名）
@AutoSpi(value = "com.chua.common.support.mock.MockString") // 声明 SPI 接口，编译期生成索引
public class MyMockString implements MockString {
    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int length = environment.length();                  // 从环境取长度
        return "X" + environment.nextInt(100);              // 用环境随机数生成数据
    }
}
```

说明：

1. `@Spi` 填生成器名称（支持数组多别名），调用方按该名称走 `MockStringFactory.generate("my-generator")`；名称匹配大小写不敏感。
2. `META-INF/extensions/com.chua.common.support.mock.MockString` 索引由 `utils-support-ast-starter` 的 `AutoSpiAstProcessor` 注解处理器在编译期自动生成，无需手写；若不使用 `@AutoSpi`，也可自行在 `META-INF/extensions/` 下登记实现类。
3. 环境参数（随机源、长度、关键词等）通过 `MockEnvironment` 注入，不要在生成器里自建 `Random`，否则无法复现。
4. 可用 `@SpiDefault` 声明默认实现（内置默认实现为 `RandomMockString`）；注意门面 `getMockString` 对未注册名称返回 `null`、不自动回退默认实现。

---

## 9. 构建与依赖

模块坐标与父子关系（来自模块 POM）：

- `com.chua:utils-support-mock-starter:4.0.0.51`
- 父模块：`com.chua:utils-support-extra-parent:4.0.0.51`（扩展模块父模块）
- 根 POM：`com.chua:utils-support-parent-starter:4.0.0.51`，统一 `JDK 25`、`UTF-8` 源码编码

依赖：

| 依赖 | 范围 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | `MockString` / `MockEnvironment` 契约、`ServiceProvider` SPI 装配、`Wraps` 等基础能力 |
| `com.chua:utils-support-ast-starter` | provided | 仅编译期：`@AutoSpi` 注解 + `AutoSpiAstProcessor` 自动生成 `META-INF/extensions` SPI 索引 |
| `org.projectlombok:lombok` | provided（根 POM 继承） | 编译期注解处理 |

编译命令（在本仓根目录或本模块目录执行）：

```bash
mvn -o -q compile -DskipTests
```

---

## 10. 边界：这些能力不在本模块

| 不在本模块 | 归属 / 说明 |
| --- | --- |
| `MockString`、`MockEnvironment` 契约定义 | 在 `utils-support-common-starter`（`com.chua.common.support.mock` 包）；本模块只提供门面与实现 |
| 对象级 / 方法级 mock（打桩、参数校验、动态代理） | 本模块源码只包含字符串生成器与门面，不含任何 mock 框架能力 |
| 图片二进制 / 文件内容生成 | `ImageMockString` 只返回图片服务 URL（picsum.photos / loremflickr.com），不下载、不生成图片内容 |
| 真实可用的证件号 / 卡号 / 号码 | 手机号、身份证、银行卡、车牌等仅保证**格式合法**，源码注释明确「仅用于测试数据填充，不保证真实可用」 |
| Spring Boot 自动装配 / 配置属性 | 无（见第 7 节） |
| 非字符串类型的随机数据（随机对象图、JSON 结构树等） | 本模块只产出 `String`；结构化测试数据请在上层自行组装 |
