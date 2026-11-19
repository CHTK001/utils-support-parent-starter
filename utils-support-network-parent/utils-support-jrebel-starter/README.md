# utils-support-jrebel-starter

> utils 体系的 **JRebel 许可证服务器实现**：内嵌一个兼容 jrebel/xrebel License Server 协议的 HTTP 服务端，用于自建许可证服务。含 RSA/备用双路签名、租约（lease）生成与校验、GUID 生成、旧版 ticket 协议端点。
>
> 版本：`4.0.0.51`（随父模块 `utils-support-network-parent`）｜JDK：`25`（继承仓库 `java.version=25`）｜包名：`com.chua.jrebel.support`（子包 `sign` / `service` / `server`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 快速开始](#3-快速开始)
- [4. 核心 API](#4-核心-api)
- [5. HTTP 端点参考](#5-http-端点参考)
- [6. 配置项](#6-配置项)
- [7. SPI 扩展点](#7-spi-扩展点)
- [8. 构建与依赖](#8-构建与依赖)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-jrebel-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

POM 描述：`JRebel 许可证服务器实现，提供自建许可证服务功能`。类级 Javadoc 标注 `@since 4.0.0.42`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 一个**许可证服务端**：模拟 jrebel/xrebel License Server 的租约、校验、ping、GUID、ticket 端点 | 不是 JRebel 客户端/agent，也不是 IDE 插件 |
| 协议 | 基于 JDK `com.sun.net.httpserver.HttpServer` 的 HTTP/JSON（ticket 端点为 XML） | 不含 TCP/UDP/WebSocket 等其他协议服务 |
| 依赖 | 只依赖 `utils-support-common-starter`（Server/Json/Wraps 等基础设施）与 Lombok | 不依赖 Spring，不是 Spring Boot 自动装配 starter |
| 状态 | 租约缓存为进程内 `ConcurrentHashMap`，并提供 GraalVM native-image 反射配置 | 无数据库、无持久化、无外部网络调用 |

### 1.3 源码构成

```
src/main/java/com/chua/jrebel/support/
├── server/JRebelHttpServer.java        # HTTP 服务器：路由注册、请求分发、JDK HttpServer 生命周期
├── service/JRebelLicenseService.java   # 许可证服务：GUID、租约创建/校验/释放、ping、缓存
└── sign/JRebelSign.java                # 签名工具：MD5withRSA 主路径 + BigInteger 备用路径
src/main/resources/META-INF/native-image/com.chua/utils-support-jrebel-starter/
└── reflect-config.json                 # GraalVM native-image 反射注册（两个类全量成员）
```

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 启动许可证服务器 | `new JRebelHttpServer(setting)` + `start()` | `ServerSetting` 配 host/port，见[快速开始](#3-快速开始) |
| 创建租约 | `POST /jrebel/leases` 或 `POST /agent/leases` | 返回带签名的租约 JSON，默认有效期 180 天 |
| 校验连接 | `GET /jrebel/validate-connection` | 固定返回 `SUCCESS` 与服务器信息 |
| 心跳保活 | `GET /rpc/ping.action` | 返回 `createPingResponse()` 的 JSON |
| 生成 GUID | `GET /guid?clientId=...` 或 `JRebelLicenseService.generateGuid(clientId)` | 同一 clientId 稳定生成同一 GUID |
| 旧版 ticket 协议 | `GET /rpc/obtainTicket.action`、`GET /rpc/releaseTicket.action` | 返回 XML |
| 直接调用签名 | `JRebelSign.sign(content)` / `toLeaseCreateJson(...)` | 优先 MD5withRSA，失败自动切备用算法 |
| 服务级 API | `JRebelLicenseService` 的 `createLeaseResponse` / `validateLease` / `releaseLease` / `createPingResponse` / `getCachedLicenseCount` | 不起 HTTP 服务也可直接调用 |

---

## 3. 快速开始

### 3.1 启动许可证服务器

`JRebelHttpServer` 类注释中的示例即最短可运行代码：

```java
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.jrebel.support.server.JRebelHttpServer;

ServerSetting setting = ServerSetting.builder()
        .host("0.0.0.0")
        .port(8080)
        .build();

AbstractServer server = new JRebelHttpServer(setting);
server.start();        // 监听 http://0.0.0.0:8080，构造时已注册全部端点
// ...
server.stop();
```

启动后即可用 jrebel/xrebel 客户端把 License Server 地址指向 `http://<host>:8080`。

### 3.2 直接使用服务类

不启动 HTTP 服务时，可直接使用 `JRebelLicenseService`：

```java
import com.chua.jrebel.support.service.JRebelLicenseService;
import java.util.Map;

JRebelLicenseService service = new JRebelLicenseService();

String guid = service.generateGuid("client-1");          // 同一 clientId 结果稳定
Map<String, Object> lease = service.createLeaseResponse(
        System.currentTimeMillis(), "user", guid, true);  // 签名后的租约响应
Map<String, Object> ping = service.createPingResponse();
service.validateLease(guid);
service.releaseLease(guid);
service.getCachedLicenseCount();
```

---

## 4. 核心 API

### 4.1 JRebelHttpServer（`com.chua.jrebel.support.server`）

继承 `AbstractServer`，注解 `@Spi("jrebel")`、`@SpiDescribe("JRebel 许可证 HTTP 服务器")`、`@SpiSupport("http")`。

| 成员 | 说明 |
| --- | --- |
| `JRebelHttpServer(ServerSetting)` | 默认构造，内部新建 `JRebelLicenseService` |
| `JRebelHttpServer(ServerSetting, JRebelLicenseService)` | 注入自定义许可证服务；传 `null` 时回退新建 |
| `start()` / `stop()` / `isRunning()` | 继承自 `AbstractServer` 的 `final` 生命周期方法 |
| `getProtocolType()` | 返回 `ProtocolType.HTTP` |
| `getSetting()` | 返回构造时传入的 `ServerSetting` |

构造时通过 `registerMapping(path, HttpMethod, handler)` 注册 7 个端点（见[第 5 节](#5-http-端点参考)）。`doStart()` 中先调用 `rejectStreamEncryption(...)`（本实现不走流加密，TLS 需通过 `ServerSetting#ssl` 配置），再创建 `com.sun.net.httpserver.HttpServer`：监听 `host:port`、上下文为 `contextPath`、线程池核心/最大线程数均为 `workerThreads`；`doStop()` 调用 `httpServer.stop(0)`。

每个请求被包装为 `HttpServerRequest` / `HttpServerResponse`（`network.server.impl` 包）后进入统一分发， finally 调用 `response.complete()`。

### 4.2 JRebelLicenseService（`com.chua.jrebel.support.service`）

| 方法 | 说明 |
| --- | --- |
| `String generateGuid(String clientId)` | `clientId` 为空返回随机 UUID（大写）；否则按 `UUID.nameUUIDFromBytes(clientId.getBytes())` 生成稳定大写 GUID |
| `Map<String,Object> createLeaseResponse(long clientRandomness, String username, String guid, boolean offline)` | 创建租约响应：有效期 `now + 180 天`（`DEFAULT_VALID_DAYS`），调用 `JRebelSign.toLeaseCreateJson(...)` 签名，并将 `LicenseInfo` 放入缓存 |
| `Map<String,Object> validateLease(String guid)` | 校验租约：缓存命中时 `company` 取该租约用户名；未命中时 `company` 为 `"JRebel"`；两者 `statusCode` 均为 `SUCCESS` |
| `Map<String,Object> createPingResponse()` | ping 响应，字段同公共子集 |
| `boolean releaseLease(String guid)` | 从缓存移除租约，命中返回 `true` |
| `int getCachedLicenseCount()` | 当前缓存租约数量 |

固定值：`serverVersion = "3.2.4"`、`serverProtocolVersion = 2`、`serverGuid = "a]\%Qq@Yq/4~}Z^sT"`（常量 `SERVER_GUID`）、`groupType = "managed"`、`statusCode = "SUCCESS"`、`licenseType = 1`、`evaluationLicense = false`、`seatPoolType = "standalone"`、`canGetLease = true`、`company`（username 为空时取 `"JRebel"`）。

`createLeaseResponse` 返回完整字段：上述公共子集 + `id=1`、`signature`、`serverRandomness`（当前毫秒）、`statusMessage=null`、`licenseValidFrom`/`licenseValidUntil`（epochMilli）、`offline`、`validFrom`/`validUntil`（UTC，格式 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`）。

内部静态类 `LicenseInfo`：`getGuid()` / `getUsername()` / `getValidFrom()` / `getValidUntil()` / `isOffline()`。

### 4.3 JRebelSign（`com.chua.jrebel.support.sign`）

| 方法 | 说明 |
| --- | --- |
| `String sign(String content)` | 主路径：`MD5withRSA` 签名后 Base64 编码；私钥不可用或签名异常时自动切换备用路径 |
| `String toLeaseCreateJson(long clientRandomness, String guid, boolean offline, String validFrom, String validUntil)` | 拼接 `clientRandomness;guid;offline;validFrom;validUntil` 后调用 `sign` |

实现细节：构造时用内置 PKCS8 Base64 私钥初始化 `KeyFactory.getInstance("RSA")`；失败仅打 debug 日志（「使用备用签名方式」）。备用路径为内置 36 进制 `PRIVATE_EXPONENT` / `MODULUS` 的 `BigInteger.modPow`，结果 Base64 编码；备用也失败时返回空串 `""`。

---

## 5. HTTP 端点参考

所有 JSON 端点返回 `Content-Type: application/json`、状态码 200；ticket 端点返回 `text/xml`。

| 方法 | 路径 | 处理器 | 说明 |
| --- | --- | --- | --- |
| POST | `/jrebel/leases` | `handleCreateLease` | 创建租约 |
| POST | `/agent/leases` | `handleCreateLease` | 同上（agent 路径别名） |
| GET | `/jrebel/validate-connection` | `handleValidateConnection` | 固定返回服务器信息（`serverGuid` 与 `SERVER_GUID` 同值） |
| GET | `/rpc/ping.action` | `handlePing` | 心跳，返回 `createPingResponse()` |
| GET | `/guid` | `handleGenerateGuid` | 按 `clientId` 查询参数生成 GUID，返回 `{guid, status:"SUCCESS"}` |
| GET | `/rpc/obtainTicket.action` | `handleObtainTicket` | 旧协议取票，返回 XML |
| GET | `/rpc/releaseTicket.action` | `handleReleaseTicket` | 旧协议释放票，返回 XML |

### 5.1 创建租约请求参数

`handleCreateLease` 先解析 JSON 请求体（解析失败按空 Map 处理），缺失时回退查询参数：

| 参数 | 来源 | 默认 |
| --- | --- | --- |
| `username` | body 或 query | `null`（`company` 落为 `"JRebel"`） |
| `guid` | body 或 query | 为空时由 `generateGuid(username)` 生成 |
| `offline` | body（兼容键 `offline1`） | `true` |
| `clientRandomness` | body | `System.currentTimeMillis()` |

响应即 `createLeaseResponse(...)` 的完整 JSON（含 `signature`）。

### 5.2 ticket 端点（XML）

`/rpc/obtainTicket.action` 读取 `salt`（为空则随机 UUID）与 `userName`，返回：

```xml
<ObtainTicketResponse>
    <message></message>
    <responseCode>OK</responseCode>
    <salt>...</salt>
    <ticketId>...</ticketId>
    <ticketProperties>
licensee=...
licenseType=0
    </ticketProperties>
</ObtainTicketResponse>
```

`/rpc/releaseTicket.action` 读取 `ticketId`，固定返回 `ReleaseTicketResponse` / `responseCode=OK`。

---

## 6. 配置项

本模块**无自有配置项**：三个源文件均未读取配置属性、环境变量或系统属性，行为常量（`DEFAULT_VALID_DAYS=180`、`serverVersion="3.2.4"`、`SERVER_GUID`、日期格式等）均为源码硬编码。

服务器行为由构造参数 `ServerSetting`（来自 `utils-support-common-starter`）控制，本模块实际读取的字段及默认值：

| 字段 | 默认值 | 本模块用途 |
| --- | --- | --- |
| `host` | `0.0.0.0` | 监听地址 |
| `port` | `8080` | 监听端口 |
| `contextPath` | `/` | `HttpServer.createContext` 根上下文 |
| `workerThreads` | CPU×2 | 请求处理线程池核心/最大线程数 |
| `backlog` | `128` | `HttpServer.create(addr, backlog)` |
| `maxRequestSize` | `10MB` | 传入 `HttpServerRequest` |
| `charset` | `UTF-8` | 传入 `HttpServerRequest` |
| `ssl` | 新建 `SslConfig` | TLS 配置；本实现不启用流加密，TLS 需按 `ServerSetting#ssl`（`SslConfig.auto` 或 keyStore/pem）在 Setting 层配置 |

---

## 7. SPI 扩展点

- `JRebelHttpServer` 以 `@Spi("jrebel")` + `@SpiSupport("http")` 声明为 `AbstractServer`/`Server` 体系下的一个 HTTP 协议实现，SPI 描述为「JRebel 许可证 HTTP 服务器」。本模块**未随附 `META-INF/extensions` 登记文件**，常规用法是直接 `new JRebelHttpServer(setting)`；如需纳入上层 SPI 装配，由使用方按 `Server` SPI 的登记方式自行注册。
- 可扩展点：`JRebelHttpServer(ServerSetting, JRebelLicenseService)` 允许替换许可证服务实现（自定义租约策略、缓存行为等）。
- GraalVM native-image：`reflect-config.json` 已注册 `JRebelHttpServer` 与 `JRebelLicenseService` 的全部构造器、方法、字段的反射访问。

---

## 8. 构建与依赖

### 8.1 编译要求

- JDK 25（继承仓库父 POM `java.version=25`）。
- 源码 UTF-8、无 BOM；Lombok 作为 `provided` 依赖参与编译。

```bash
# 在 utils-support-parent-starter 仓库根执行
mvn -pl utils-support-network-parent/utils-support-jrebel-starter -am clean package
```

### 8.2 依赖

| 依赖 | 范围 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | `AbstractServer`/`ServerSetting`、`Json`、`Wraps`、`StringUtils`、SPI 注解等 |
| `org.projectlombok:lombok` | provided | `@Slf4j` 日志 |
| 单元测试依赖 | — | 源码未提供（POM 中仅保留「JRebel 鉴权端点的真实 HTTP 请求」注释占位，未声明测试依赖与测试目录） |

---

## 9. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| JRebel/XRebel 客户端 agent、IDE 插件 | 不在本模块；本模块只是服务端 |
| 与 JRebel 官方 License Server 通信 | 不存在；本模块自身即「自建许可证服务端」，不代理、不转发官方服务 |
| 租约/许可证持久化 | 无；缓存为进程内 `ConcurrentHashMap`，进程重启后租约状态清零 |
| TCP/UDP/WebSocket/FTP 等其他协议服务 | 在 `utils-support-common-starter` 的 `network.server` 体系 |
| TLS 证书加载 | 由 `ServerSetting#ssl`（`SslConfig`）承担；本模块 `doStart` 明确拒绝流加密方式 |
| Spring Boot 自动装配 | 无；本模块虽名为 starter，实为 utils 体系纯 Java 库，无 Spring 依赖与自动配置类 |
| 签名私钥/模数的外部化配置 | 无；`JRebelSign` 的私钥、`PRIVATE_EXPONENT`、`MODULUS` 均为源码内置常量 |

---

## 许可证

Apache License 2.0
