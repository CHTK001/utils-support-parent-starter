# utils-support-ip2region-starter

IP2Region IP 地理位置查询模块：基于 ip2region xdb 数据库文件实现毫秒级 IP 定位，自解析 xdb 二进制格式，无第三方外部依赖。

- **坐标**：`com.chua:utils-support-ip2region-starter:4.0.0.51`（版本继承自 `utils-support-filesystem-parent`）
- **JDK**：25（仓库根 POM `java.version=25`）
- **包名**：`com.chua.ip2region.support`
- **实现类**：`Ip2regionIpPosition`（`@since 4.0.0.42`，实现 `com.chua.common.support.network.ip.IpPosition`）

---

## 目录

- 它是什么 / 不是什么
- 核心 API 与速查表
- 快速开始
- 配置项
- SPI 扩展点
- 构建与依赖
- 边界（这些能力不在本模块）

---

## 它是什么 / 不是什么

| 它是 | 它不是 |
|------|--------|
| `IpPosition` SPI 的 `ip2region` 实现（`@Spi("ip2region")`） | Spring Boot 自动装配模块（本模块无 `AutoConfiguration`、无配置属性类） |
| xdb 二进制格式的自解析实现：小端序读取、索引区二分查找 | ip2region 官方客户端的封装（未依赖任何 ip2region 官方库） |
| 支持两种数据库来源：`classpath:` 资源与本地文件路径 | xdb 数据文件的持有者（数据文件由资源包 `utils-support-resource-ip2region` 提供） |
| 返回 国家 / 省份 / 城市 / 运营商 四级归属信息 | 经纬度数据源（不提供经纬度，反查为接口默认空实现） |
| 纯本地查询，不发起任何网络请求 | 在线 IP 归属地 API 的客户端 |

---

## 核心 API 与速查表

返回值 `IpLocation`（位于 `com.chua.common.support.network.ip`）含四个字段：`country`、`province`、`city`、`isp`；查询无结果、IP 为空或非法时 `query` 返回 `null`。

| 你想做什么 | 入口 |
|------------|------|
| IP → 地理位置 | `new Ip2regionIpPosition().query("114.114.114.114")` → `IpLocation` |
| IP → 城市信息（附带原始 IP） | `position.getCity(ip)` → `GeoCity`（接口默认方法，内部转发 `query`） |
| 指定 xdb 文件构造 | `new Ip2regionIpPosition("classpath:ip2region.xdb")`（也支持本地文件路径） |
| 从配置对象构造 | `new Ip2regionIpPosition(geoSetting)`（取 `GeoSetting#getDatabaseFile`） |
| SPI 按名称获取实现 | `ServiceProvider.of(IpPosition.class).getNewExtension("ip2region", geoSetting)` |
| 获取实现类型标识 | `position.getType()`（接口默认方法，返回类 SimpleName） |
| 两点距离 / 点是否在圆内 | `position.distance(lat1, lng1, lat2, lng2)` / `position.inCircle(...)`（接口默认方法） |
| 经纬度 → 地理信息 | `position.reverseGeocode(lat, lng)`（本实现未覆写，恒返回 `GeoCity.EMPTY`） |

---

## 快速开始

前置条件：类路径上必须存在 `ip2region.xdb`（通常由资源包 `com.chua:utils-support-resource-ip2region` 提供）。若类路径找不到该资源，构造时抛出 `RuntimeException`，提示自行添加 `com.chua:utils-support-resource-ip2region:4.0.0.43` 依赖。

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ip2region-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

```java
import com.chua.common.support.network.ip.IpLocation;
import com.chua.common.support.network.ip.IpPosition;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.ip2region.support.Ip2regionIpPosition;

// 方式一：直接构造，默认读取 classpath:ip2region.xdb
IpPosition position = new Ip2regionIpPosition();
IpLocation location = position.query("114.114.114.114");
System.out.println(location);   // 形如：国家省份城市 运营商

// 方式二：SPI 按名称获取（与上层 plugin.ip.ip-type=ip2region 的选择逻辑一致）
GeoSetting geoSetting = GeoSetting.builder().build();   // 空配置 → 回落默认类路径资源
IpPosition viaSpi = ServiceProvider.of(IpPosition.class)
        .getNewExtension("ip2region", geoSetting);
```

自定义数据库位置时，`classpath:` 前缀走类加载器读取，无前缀按本地文件读取：

```java
IpPosition local = new Ip2regionIpPosition("classpath:ip2region.xdb");
IpPosition file  = new Ip2regionIpPosition("D:/data/ip2region.xdb");
```

---

## 配置项

本模块自身**没有** Spring 配置属性类（源码无 `@ConfigurationProperties`，resources 目录仅含 SPI 登记文件）。数据库路径由上层经 `GeoSetting` 传入，对应上层共用的配置键：

| 配置键 | 说明 | 归属 |
|--------|------|------|
| `plugin.ip.database-file` | 数据库文件路径。本模块只接受以 `.xdb` 结尾的值；为空或指向其它格式（如默认的 `classpath:qqwry.dat`）时，回落到内置的 `classpath:ip2region.xdb` | 键定义于 `spring-support-common-starter` 的 `IpProperties`（前缀 `plugin.ip`），经 `GeoSetting#getDatabaseFile` 传入本模块 |
| `plugin.ip.ip-type` | SPI 实现选择，默认 `ip2region` | 同上 |
| `plugin.ip.enable` | 是否启用 IP 解析 | 同上（本模块不直接读取） |

---

## SPI 扩展点

- **契约接口**：`com.chua.common.support.network.ip.IpPosition`（位于 `utils-support-common-starter`）。
- **登记文件**：`META-INF/extensions/com.chua.common.support.network.ip.IpPosition`，内容为 `ip2region=com.chua.ip2region.support.Ip2regionIpPosition`。
- **加载方式**：`ServiceProvider.of(IpPosition.class)`，按 `@Spi` 名称（本实现为 `ip2region`）或类型获取实例。
- **替换/新增实现**：实现 `IpPosition` 接口、标注 `@Spi("你的名称")`，并在自己的 `META-INF/extensions/com.chua.common.support.network.ip.IpPosition` 文件中登记 `你的名称=实现类全限定名`，即可与本实现并存，由 `plugin.ip.ip-type` 等上层选择逻辑决定使用哪个。

xdb 区域字符串格式为 `国家|省份|城市|ISP|国家码`；某级为 `0` 表示未知，保留地址（内网/回环）标记为 `Reserved`——两者在本实现中统一归一化为空串。

---

## 构建与依赖

- **唯一编译依赖**：`com.chua:utils-support-common-starter`（提供 `IpPosition`/`IpLocation`/`GeoSetting` 契约与 `ServiceProvider` SPI 框架）。
- **运行期数据依赖**：`com.chua:utils-support-resource-ip2region`（提供 `ip2region.xdb`；源码提示版本 `4.0.0.43`）。
- **构建命令**（在 `utils-support-parent-starter` 仓库内）：

```bash
mvn -o -q compile -DskipTests
```

---

## 边界（这些能力不在本模块）

| 能力 | 说明 |
|------|------|
| IPv6 解析 | 不支持。内部仅将点分十进制 IPv4 转为 long，段数不为 4 或数值非法时查询返回 `null` |
| 经纬度反查 | 未支持。`reverseGeocode(lat, lng)` 为接口默认实现，恒返回 `GeoCity.EMPTY` |
| 经纬度/精度数据 | xdb 区域串只有 国家/省份/城市/ISP/国家码，无坐标信息 |
| 在线查询 | 无任何 HTTP/网络调用，全部为本地二进制查找 |
| Spring 自动装配 | 本模块不提供 `AutoConfiguration` 与配置属性类；装配行为由上层（如 `spring-support-common-starter` 的 `IpProperties` + 服务实现）决定 |
| xdb 数据文件 | 不随本模块分发，需自行引入 `utils-support-resource-ip2region` 资源包或放置 xdb 文件 |
| GeoIP2 / qqwry 等其它数据库 | 分别由 `utils-support-geoip2-starter`、`utils-support-qqwry-starter` 等同层模块提供，本模块只做 ip2region xdb |
