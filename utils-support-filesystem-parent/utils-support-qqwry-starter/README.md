# utils-support-qqwry-starter

> utils 体系的**纯真 IP 数据库（QQWry）离线查询模块**：加载 `qqwry.dat`，对 IPv4 地址做二分查找，返回国家/省/市/运营商（ISP）四段归属地信息；并以 SPI 名 `qqwry` 接入 common 层的 `IpPosition` 扩展点。
>
> 版本：`4.0.0.51`（继承父模块 `utils-support-filesystem-parent`）｜JDK：`25`（根 POM `maven.compiler.source/target=25`）｜包名：`com.chua.qqwry.support`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `qqwry.dat` 纯真 IP 库的**离线查询器**：全量载入内存，索引区二分查找 | 不是在线 IP 归属地服务的客户端 |
| 结果 | 返回 common 层 `IpLocation`：`country`（国家或地区）、`province`（省份）、`city`（城市）、`isp`（运营商） | 不提供经纬度、行政区划编码等扩展字段（源码未提供） |
| 地址范围 | 按 IPv4 点分十进制解析（`ipToLong` 固定按 4 段拆分） | 不解析 IPv6 |
| 接入方式 | SPI 实现：`@Spi("qqwry")` 的 `IpPosition`；也可直接 new `QQWryReader` | 不定义新的 SPI 接口，不提供配置化装配 |
| 数据来源 | 数据文件由外部提供（自备 `qqwry.dat`，或依赖 `utils-support-resource-qqwry`） | 本模块**不内置**任何 `.dat` 数据文件 |

实现细节（与源码一致）：文件头 8 字节为小端序的索引区起止偏移；每条索引 7 字节（起始 IP 3 字节 + 结束 IP 3 字节 + 记录偏移 3 字节）；记录文本按 **GBK** 解码；结果会剔除 `CZ88.NET` 标记，并从区域串中拆出 ISP（首个空白段）与省/市（按「省 / 市 / 自治区 / 特别行政区」关键词切分）。

---

## 2. 核心 API 与速查表

模块只有两个公开类，均位于 `com.chua.qqwry.support`，均 `@since 4.0.0.42`。

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 从文件路径构建查询器 | `new QQWryReader(String filePath)` | 一次性读入整个库文件；失败抛 `IOException` |
| 从字节数组构建查询器 | `new QQWryReader(byte[] data)` | 适合已有文件内容的场景 |
| 按字符串 IP 查询 | `reader.query("114.114.114.114")` | 返回 `IpLocation`，未命中返回 `null` |
| 按长整型 IP 查询 | `reader.query(long ip)` | 同上，避免重复做字符串解析 |
| IP 字符串转 long | `QQWryReader.ipToLong("1.2.3.4")` | 静态方法，点分十进制 → 长整型 |
| 总记录数 | `reader.getTotalRecords()` | 由索引区长度 / 7 计算得出 |
| SPI 门面（默认库路径） | `new QqwryIpPosition()` | 默认加载工作目录下的 `qqwry.dat` |
| SPI 门面（指定库路径） | `new QqwryIpPosition(String dbPath)` | 加载失败抛 `RuntimeException`，并提示添加 `com.chua:utils-support-resource-qqwry:4.0.0.43` |
| 按名字取 SPI 实现 | `ServiceProvider.of(IpPosition.class).getExtension("qqwry")` | common 层标准 SPI 装配方式 |

`QQWryReader` 实现了 `java.io.Closeable`，`close()` 为空实现（数据已全量在内存，无需释放），可安全用于 try-with-resources。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-qqwry-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>

<!-- 数据文件（qqwry.dat，约 26MB）；也可以自备文件、直接传路径 -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-resource-qqwry</artifactId>
    <version>4.0.0.43</version>
</dependency>
```

### 3.2 直接使用查询器

```java
import com.chua.common.support.network.ip.IpLocation;
import com.chua.qqwry.support.QQWryReader;

try (QQWryReader reader = new QQWryReader("qqwry.dat")) {
    System.out.println(reader.getTotalRecords());   // 总记录数

    IpLocation loc = reader.query("114.114.114.114");
    if (loc != null) {
        System.out.println(loc.getCountry() + loc.getProvince() + loc.getCity() + " " + loc.getIsp());
    }
}
```

### 3.3 通过 SPI 使用

```java
import com.chua.common.support.network.ip.IpLocation;
import com.chua.common.support.network.ip.IpPosition;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.qqwry.support.QqwryIpPosition;

// 方式一：直接构造（默认读取工作目录下的 qqwry.dat）
IpPosition position = new QqwryIpPosition();
IpLocation loc = position.query("114.114.114.114");

// 方式二：按 SPI 名获取
IpPosition byName = ServiceProvider.of(IpPosition.class).getExtension("qqwry");
```

> `new QqwryIpPosition()` 按**相对工作目录**的 `qqwry.dat` 读取文件；若文件不存在，会抛出 `RuntimeException`，异常信息为：`Failed to load QQWry database: qqwry.dat，请自行添加依赖 com.chua:utils-support-resource-qqwry:4.0.0.43`。本模块未内置「从 classpath 自动解出该资源」的工具类（源码未提供），请自行定位数据文件后传入路径，或用 `new QQWryReader(byte[])` 传入文件内容。

---

## 4. 配置项

**无。**

依据：本模块源码未定义任何配置类、配置键或系统属性读取；数据库文件路径只能通过构造参数传入，缺省值是 `QqwryIpPosition` 中的私有常量 `DEFAULT_DB = "qqwry.dat"`。字符集（GBK）、解析模式（`0x01` / `0x02` 重定向）均为源码内固定逻辑，不暴露为可配置项。

---

## 5. SPI 扩展点

本模块**不定义**新的 SPI 接口，它实现的是 common 层 `com.chua.common.support.network.ip.IpPosition` 扩展点：

- 实现类：`com.chua.qqwry.support.QqwryIpPosition`，注解 `@Spi("qqwry")`；
- 登记文件：`src/main/resources/META-INF/extensions/com.chua.common.support.network.ip.IpPosition`，内容：

```
qqwry=com.chua.qqwry.support.QqwryIpPosition
```

- 获取方式：`ServiceProvider.of(IpPosition.class).getExtension("qqwry")`。

如需接入其它 IP 库（如 ip2region、GeoIP2），不在本模块扩展；见同仓对应 starter（下表）。

---

## 6. 构建与依赖

- Maven 坐标：`com.chua:utils-support-qqwry-starter:4.0.0.51`，父模块 `utils-support-filesystem-parent:4.0.0.51`。
- 编译环境：JDK 25（根 POM 统一 `maven.compiler.source/target=25`）。
- 编译期依赖：仅 `com.chua:utils-support-common-starter`（`IpLocation` / `IpPosition` / `Spi` / `ServiceProvider` / `StringUtils` 均来自该模块）。
- 运行期数据：`qqwry.dat`。可添加 `com.chua:utils-support-resource-qqwry:4.0.0.43`（位于 `utils-support-resource-parent` 下，jar 内携带约 26MB 的纯真 IP 数据库），或自备数据文件后按路径/字节数组构造。
- 目录归属：本模块位于 `utils-support-filesystem-parent` 目录下，但该父 POM 的 `<modules>` 清单**未包含** `utils-support-qqwry-starter`（同目录的 `utils-support-ip2region-starter`、`utils-support-geoip2-starter` 等在列）；单独构建时请直接在本模块目录执行 `mvn` 命令。

```
utils-support-qqwry-starter
├── utils-support-common-starter        （编译依赖：SPI 契约与工具）
└── qqwry.dat                           （运行期数据：自备，或来自 utils-support-resource-qqwry）
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 去处 |
| --- | --- |
| `IpPosition` / `IpLocation` 契约与 SPI 装配 | `utils-support-common-starter`（`com.chua.common.support.network.ip`、`com.chua.common.support.spi`） |
| `qqwry.dat` 数据文件本身 | `utils-support-resource-qqwry`（或自备） |
| ip2region 离线 IP 归属地查询 | `utils-support-ip2region-starter` |
| GeoIP2 / MaxMind 库查询 | `utils-support-geoip2-starter` |
| IPv6 解析、经纬度、行政区划编码、在线校准、查询缓存、批量/HTTP 门面 | 源码未提供，本模块均不实现 |
