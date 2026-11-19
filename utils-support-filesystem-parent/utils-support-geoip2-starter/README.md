# utils-support-geoip2-starter

> 基于 MaxMind GeoLite2 / GeoIP2 **mmdb 数据库**的离线 IP 地理位置查询模块：提供 `IpPosition` SPI 实现（注册名 `geoip2`），按 zh-CN 优先返回 **国家 / 省份 / 城市**。
>
> 版本：`4.0.0.51`｜JDK：`25`｜包名：`com.chua.geoip2.support`

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
| 定位 | MaxMind GeoLite2 / GeoIP2 离线 IP 地理位置查询 SPI 实现（基于 mmdb） | 在线 IP 查询服务、HTTP 接口 |
| 能力 | IP → 国家 / 省份 / 城市（zh-CN 名优先，缺省回退 ISO 编码 / 原名） | ISP 运营商识别、经纬度反向地理编码、代理（VPN/TOR/机房）检测 |
| 数据 | 读取 `GeoLite2-City.mmdb`（类路径资源、本地文件或输入流） | 不内嵌 mmdb 数据文件（数据由 `utils-support-resource-geolite2` 提供） |
| 集成 | 通过 `ServiceProvider` SPI 装配，也可直接 `new` | 无 Spring 自动装配、无配置绑定 |

模块档案（均来自源码）：

| 项 | 值 |
| --- | --- |
| 实现类 | `com.chua.geoip2.support.GeoLite2IpPosition` |
| 实现接口 | `com.chua.common.support.network.ip.IpPosition`（位于 `utils-support-common-starter`） |
| SPI 注册名 | `geoip2`（`@Spi("geoip2")`） |
| 默认数据文件 | 类路径资源 `GeoLite2-City.mmdb` |
| 引入版本 | `@since 4.0.0.42` |
| 底层库 | `com.maxmind.geoip2:geoip2:4.3.0`（`DatabaseReader`） |

---

## 2. 核心 API 与速查表

### 2.1 构造方式（三选一）

| 构造 | 行为 | 失败时 |
| --- | --- | --- |
| `new GeoLite2IpPosition()` | 从类路径加载默认资源 `GeoLite2-City.mmdb` | 抛 `IllegalStateException`：未找到时提示「请添加 utils-support-resource-geolite2 依赖」 |
| `new GeoLite2IpPosition(String dbPath)` | 按文件路径加载 mmdb（`Path.of(dbPath).toFile()`） | 抛 `IllegalStateException`：初始化 GeoLite2 Reader 失败 |
| `new GeoLite2IpPosition(InputStream mmdbStream)` | 从输入流加载 mmdb（适合类路径 / 网络加载） | 抛 `IllegalStateException`：初始化 GeoLite2 Reader 失败 |

`DatabaseReader` 实例在对象内复用（源码字段注释：线程安全，可复用）。

### 2.2 查询方法

| 方法 | 行为 |
| --- | --- |
| `IpLocation query(String ip)` | IP 为空白返回 `null`；用 `InetAddress.getByName(ip)` 解析后调 `reader.city(...)`；任何异常记录 error 日志（`GeoLite2 查询失败: ip=...`）并返回 `null`，不向上抛 |
| `GeoCity reverseGeocode(double latitude, double longitude)` | GeoLite2 不支持反向地理编码，恒返回 `GeoCity.EMPTY` |

`query` 返回的 `IpLocation` 仅回填三个字段：`country` / `province` / `city`，取值优先 `zh-CN` 名称；仅当该记录的名称映射（`getNames()`）不存在时才回退 ISO 编码（国家、省份）或原始名（城市），若映射存在但没有 `zh-CN` 条目，则对应字段为 `null`；`isp` 字段不会被赋值。

接口 `IpPosition` 上的 default 方法对本实现同样可用：`getCity(ip)`（查询失败返回 `GeoCity.EMPTY`，此时不回填 `ip`；查询成功时回填 `ip` 字段）、`distance(...)`（两点距离，米）、`inCircle(...)`（点是否在圆内）。

### 2.3 常见能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| SPI 按名获取实现 | `ServiceProvider.of(IpPosition.class).getExtension("geoip2")` | 类路径下 `META-INF/extensions` 已登记 |
| 最简构造 | `new GeoLite2IpPosition()` | 读类路径 `GeoLite2-City.mmdb` |
| 指定 mmdb 路径 | `new GeoLite2IpPosition(dbPath)` | 本地文件 |
| 从流加载 mmdb | `new GeoLite2IpPosition(inputStream)` | 类路径 / 网络加载 |
| IP → 国家/省/市 | `query(ip)` → `IpLocation` | 失败返回 `null` |
| IP → GeoCity | `getCity(ip)` | 接口 default，失败返回 `GeoCity.EMPTY` |
| 经纬度 → 城市 | `reverseGeocode(lat, lng)` | 恒返回 `GeoCity.EMPTY` |
| 两点距离 / 圆内判断 | `distance(...)` / `inCircle(...)` | 接口 default，米为单位 |

---

## 3. 快速开始

### 3.1 加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-geoip2-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
<!-- GeoLite2-City.mmdb 数据模块（仓库内为占位文件，需替换为真实 mmdb，见 3.3） -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-resource-geolite2</artifactId>
    <version>4.0.0.43</version>
</dependency>
```

### 3.2 查询 IP 归属地（最短可运行示例）

```java
import com.chua.common.support.network.ip.IpLocation;
import com.chua.common.support.network.ip.IpPosition;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.geoip2.support.GeoLite2IpPosition;

public class GeoIp2Demo {
    public static void main(String[] args) {
        // 方式一：SPI 按名获取（META-INF/extensions 已登记 geoip2 实现）
        IpPosition bySpi = ServiceProvider.of(IpPosition.class).getExtension("geoip2");

        // 方式二：直接指定 mmdb 文件路径
        IpPosition byPath = new GeoLite2IpPosition("D:/data/GeoLite2-City.mmdb");

        IpLocation location = byPath.query("8.8.8.8");
        if (location != null) {
            System.out.println(location.getCountry());   // 国家（zh-CN 优先）
            System.out.println(location.getProvince());  // 省份
            System.out.println(location.getCity());      // 城市
        }
    }
}
```

### 3.3 数据文件准备

- 默认构造要求类路径存在 `GeoLite2-City.mmdb`，否则抛 `IllegalStateException`。
- `utils-support-resource-geolite2` 的资源目录当前是**占位说明**：需从 MaxMind（GeoLite2 免费下载页）下载真实 `GeoLite2-City.mmdb` 并放入该模块 `src/main/resources` 后再打包/部署。
- 不想引入数据模块时，可直接 `new GeoLite2IpPosition("本地路径/GeoLite2-City.mmdb")` 或传 `InputStream`。

---

## 4. 配置项

**无。** 依据：

- 本模块 `src/main/resources` 仅有一个文件：SPI 登记文件（见第 5 节），没有任何 `application.yml` / `properties` 配置键；
- 源码（`GeoLite2IpPosition`）没有配置类、`@ConfigurationProperties`、系统属性或环境变量读取；
- mmdb 位置完全由**构造参数**决定（文件路径或输入流），默认构造固定读类路径 `GeoLite2-City.mmdb`。

> 契约层 `utils-support-common-starter` 中定义了 `GeoSetting`（`databaseFile`、`licenseKey`，面向 MaxMind 自动下载场景），但本模块源码未引用该类，不构成配置入口。

---

## 5. SPI 扩展点

### 5.1 本模块注册的实现

登记文件：`src/main/resources/META-INF/extensions/com.chua.common.support.network.ip.IpPosition`

```properties
geoip2=com.chua.geoip2.support.GeoLite2IpPosition
```

获取方式：

```java
IpPosition position = ServiceProvider.of(IpPosition.class).getExtension("geoip2");
```

### 5.2 同接口的其它实现

- `utils-support-ip2region-starter`（同一父聚合器 `utils-support-filesystem-parent`）也在 `META-INF/extensions/com.chua.common.support.network.ip.IpPosition` 下登记了实现，注册名为 `ip2region`（实现类 `com.chua.ip2region.support.Ip2regionIpPosition`）。
- 本模块类注释写明代理检测（VPN / TOR / DataCenter）请使用 `utils-support-resource-ip2proxy-starter`，但该模块在当前工作区并不存在；工作区内现成的 IP 质量 / 代理检测实现是 `spring-support-common-starter` 的 `IP2ProxyLiteIpQualityDetector`（基于 `com.ip2proxy:ip2proxy-java` 与类路径 `IP2PROXY-LITE-PX2.BIN`）。

### 5.3 自行扩展

实现 `IpPosition` 接口，加 `@Spi("你的名字")`，并在自己模块的 `src/main/resources/META-INF/extensions/com.chua.common.support.network.ip.IpPosition` 中登记 `名字=实现类全限定名`，即可被 `ServiceProvider` 发现。

---

## 6. 构建与依赖

### 6.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-geoip2-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 6.2 依赖清单（来自 pom.xml）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 pom（`4.0.0.51`） | `IpPosition` / `IpLocation` / `GeoCity` 契约、`ServiceProvider` SPI、`StringUtils` |
| `com.maxmind.geoip2:geoip2` | `4.3.0` | `DatabaseReader`、`CityResponse` 等 mmdb 读取能力 |
| `org.projectlombok:lombok` | provided（父 pom） | `@Slf4j` 日志 |

### 6.3 构建

- 父 pom：`utils-support-filesystem-parent`（聚合于 `utils-support-parent-starter`）。
- JDK：`25`（父 pom `maven.compiler.source/target=25`）。
- 编译验证（仓库约定）：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 去向 / 说明 |
| --- | --- |
| 代理检测（VPN / TOR / DataCenter） | 本模块 javadoc 指向 `utils-support-resource-ip2proxy-starter`，但该模块当前工作区不存在；工作区内现成实现为 `spring-support-common-starter` 的 `IP2ProxyLiteIpQualityDetector` |
| ISP 运营商识别 | 本实现不回填 `IpLocation.isp`；GeoLite2-City 数据不含 ISP |
| 经纬度 → 地理信息（反向地理编码） | `reverseGeocode` 恒返回 `GeoCity.EMPTY` |
| mmdb 数据文件本身 | `utils-support-resource-geolite2`；仓库内当前为占位文件，需自行下载真实 `GeoLite2-City.mmdb` |
| 中文场景离线 IP 库（ip2region） | `utils-support-ip2region-starter`（同父聚合器） |
| Spring Boot 自动装配 / 配置绑定 / HTTP 接口 / 定时任务 | 本模块均无，纯 SPI 工具模块 |

补充说明：`query` 的入参需能被 `InetAddress.getByName` 解析（IPv4 / IPv6 字面量均可）；若传入主机名，会触发 DNS 解析，且解析或查询失败一律返回 `null`。
