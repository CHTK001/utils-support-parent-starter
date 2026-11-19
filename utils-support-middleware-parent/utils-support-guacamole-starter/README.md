# utils-support-guacamole-starter

> Apache Guacamole 远控集成的**短链客户端**：用 `GuacamoleClient` 链式登记 guacd 服务器与远控连接参数（协议、主机、端口、账号、扩展参数），签发不含敏感信息的随机短 token，并生成 / 解析会话 URL。
>
> 版本：`4.0.0.51`（继承父 POM `utils-support-middleware-parent`）｜JDK：`25`（仓库根 POM `maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.guacamole.support.client`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 规模](#13-规模)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 类型总览](#21-类型总览)
  - [2.2 常用能力速查](#22-常用能力速查)
  - [2.3 GuacamoleClient 方法](#23-guacamoleclient-方法)
  - [2.4 Builder 选项](#24-builder-选项)
  - [2.5 IssueOperation 登记链](#25-issueoperation-登记链)
  - [2.6 RemoteSpec 参数集](#26-remotespec-参数集)
  - [2.7 RemoteProtocol 协议枚举](#27-remoteprotocol-协议枚举)
  - [2.8 行为细节](#28-行为细节)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)
- [8. 对接实战经验（附）](#8-对接实战经验附)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-guacamole-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Apache Guacamole 的**部署参数模型 + 短链签发/解析**纯 Java 客户端工具 | 不是 guacamole 协议隧道实现；不包含、不启动 guacd 或 guacamole-client |
| 形态 | 单一门面类 `GuacamoleClient`（Builder 构建 + 链式登记链 `IssueOperation`） | 无 Spring 自动配置、无配置文件、无 SPI 扩展点 |
| 安全 | 敏感连接参数（主机、账号、密码）只存于客户端内存登记表；URL 仅携带随机短 token（fragment 形式，浏览器不会发送到服务器） | 不做加密存储与持久化；进程重启后已签发 token 失效 |
| 依赖 | 仅依赖 `utils-support-common-starter`（`ExpireValue` 等） | 不依赖 Spring、不依赖任何 guacamole 官方 jar |

**判断口径**：需要「给前端发一个不含敏感信息的远控入口」时用本模块；需要真正连上 guacd 跑 guacamole 协议时，由前端 guacamole-common-js 与 guacd 完成，本模块只负责参数建模与短链。

### 1.3 规模

- Java 源文件 **1** 个：`com.chua.guacamole.support.client.GuacamoleClient`（`@since 4.0.0.42`）。
- 无 `src/main/resources`（无 SPI 注册文件、无配置文件、无 `spring.factories` / `AutoConfiguration.imports`）。
- 内部类型全部嵌套在 `GuacamoleClient` 中：`Builder`、`IssueOperation`、`RemoteSpec`（record）、`RemoteProtocol`（enum）、`GuacamoleClientException`。

---

## 2. 核心 API 与速查表

### 2.1 类型总览

| 类型 | 说明 |
| --- | --- |
| `GuacamoleClient` | Guacamole 远控链式客户端（短链模式）。内部用 `ConcurrentHashMap` 维护「短 token → `ExpireValue<RemoteSpec>`」登记表，`SecureRandom` 生成 token，实例可多线程共用。 |
| `GuacamoleClient.Builder` | 构建器：guacd 地址 / Web 前端地址 / 上下文路径 / token 有效期，`build()` 时校验。 |
| `GuacamoleClient.IssueOperation` | 远控连接参数登记链：协议、主机、端口、账号、扩展参数，`issue()` 签发短 token。 |
| `GuacamoleClient.RemoteSpec` | 远控连接参数集（不可变 record），由短 token 承载，经 `resolve(token)` 取回。 |
| `GuacamoleClient.RemoteProtocol` | Guacamole 支持协议枚举：`RDP` / `VNC` / `SSH` / `TELNET`，携带 guacd 参数值与默认端口。 |
| `GuacamoleClient.GuacamoleClientException` | Guacamole 客户端异常（`RuntimeException`）。 |

### 2.2 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建客户端（全默认） | `GuacamoleClient.create()` | guacd `127.0.0.1:4822`、前端 `127.0.0.1:8080/guacamole/`、token 有效期 1 小时 |
| 自定义创建 | `GuacamoleClient.builder()...build()` | `guacdHost/guacdPort/webHost/webPort/contextPath/tokenTtl` |
| 登记 RDP 并签发短 token | `client.rdp().host(..).port(..).username(..).password(..).issue()` | 协议已预置 RDP；另有 `client.vnc()`、`client.ssh()` 快捷入口 |
| 登记任意协议 | `client.issue().protocol("ssh").host(..)...issue()` | `protocol` 接受枚举或 `rdp/vnc/ssh/telnet` 字符串（忽略大小写） |
| 生成会话 URL | `client.url(token)` | 形如 `http://host:port/guacamole/#token=xxxxxxxx`，仅含短 token |
| 反向解析参数 | `client.resolve(token)` | 返回 `Optional<RemoteSpec>`；token 未知 / 已过期 / 空白返回空 |
| 手动续期 | `client.refreshTokens()` | 按有效期续期全部未过期 token，返回续期数量 |
| 查看签发数量 | `client.tokenCount()` | 含已过期尚未清除的条目 |
| 协议默认端口 | `RemoteProtocol.of("vnc").getDefaultPort()` | RDP=3389、VNC=5900、SSH=22、TELNET=23 |
| guacd 地址串 | `client.guacdAddress()` | 形如 `127.0.0.1:4822`；前端入口用 `client.webUrl()` |

### 2.3 GuacamoleClient 方法

```java
// 工厂
static GuacamoleClient create()                          // guacd 与 Web 前端均默认本机
static GuacamoleClient create(String guacdHost, int guacdPort)
static Builder builder()

// 实例链式设置（可在 build 之后继续调整）
GuacamoleClient guacdHost(String host)                   // 不能为 null
GuacamoleClient guacdPort(int port)                      // 1-65535，越界抛 GuacamoleClientException
GuacamoleClient webHost(String host)
GuacamoleClient webPort(int port)
GuacamoleClient contextPath(String path)                 // 允许空，空时按 /guacamole 处理
String guacdAddress()                                    // "host:port"
String webUrl()                                          // "http://host:port/guacamole/"（自动补首尾 /）

// 短链操作
IssueOperation issue()                                   // 进入参数登记链（协议默认 RDP）
IssueOperation rdp()                                     // 等价 issue().protocol(RemoteProtocol.RDP)
IssueOperation vnc()
IssueOperation ssh()
String url(String token)                                 // webUrl() + "#token=" + URLEncoder.encode(token, UTF-8)
Optional<RemoteSpec> resolve(String token)               // 空白/未知/过期 → Optional.empty()
int refreshTokens()                                      // 手动续期全部未过期 token，返回数量
int tokenCount()                                         // 登记表大小（含已过期未清除的）
```

getter：`getGuacdHost()` / `getGuacdPort()` / `getWebHost()` / `getWebPort()` / `getContextPath()` / `getTokenTtl()`（Lombok `@Getter`）。

### 2.4 Builder 选项

| 方法 | 默认值 | 校验 |
| --- | --- | --- |
| `guacdHost(String)` | `127.0.0.1` | `build()` 时空白抛 `GuacamoleClientException` |
| `guacdPort(int)` | `4822` | `build()` 时校验 1-65535 |
| `webHost(String)` | `127.0.0.1` | `build()` 时空白抛 `GuacamoleClientException` |
| `webPort(int)` | `8080` | `build()` 时校验 1-65535 |
| `contextPath(String)` | `/guacamole` | 无（空值在使用时按默认路径处理） |
| `tokenTtl(Duration)` | `Duration.ofHours(1)` | 不能为 `null` 或负值，违反即抛异常 |
| `build()` | — | 校验通过后返回 `GuacamoleClient` |

### 2.5 IssueOperation 登记链

```java
IssueOperation protocol(RemoteProtocol protocol)         // 不能为 null
IssueOperation protocol(String protocol)                 // "rdp"/"vnc"/"ssh"/"telnet"，忽略大小写；不支持抛 GuacamoleClientException
IssueOperation host(String host)                         // issue() 前必须非空
IssueOperation port(int port)                            // 1-65535；不设置则用协议默认端口
IssueOperation username(String username)                 // 可空
IssueOperation password(String password)                 // 可空
IssueOperation param(String key, String value)           // 追加单个扩展参数（键或值为 null 时忽略）
IssueOperation params(Map<String, String> params)        // 批量追加（null 集合忽略，空键值对被过滤）
String issue()                                           // 登记参数集并签发短 token
```

- `protocol` 不设置时默认 `RDP`。
- `host` 未设置就调用 `issue()` 抛 `GuacamoleClientException("远控服务器地址（host）未设置")`。
- 扩展参数的键名与 guacd 配置属性一致（如 `rdp-disable-copy`）。

### 2.6 RemoteSpec 参数集

```java
public record RemoteSpec(
        RemoteProtocol protocol,        // 不能为 null
        String host,                    // 不能为 null
        Integer port,                   // 可为 null（空时取协议默认端口）
        String username,                // 可为 null
        String password,                // 可为 null
        Map<String, String> params      // 可为 null（规范构造器将其规范化为不可变 Map）
) {
    public int effectivePort()          // port != null ? port : protocol.getDefaultPort()
}
```

规范构造器（compact constructor）会 `Map.copyOf(params)`，因此 `RemoteSpec` 不可变；密码等敏感字段仅存在于客户端内存登记表，不落地到 URL。

### 2.7 RemoteProtocol 协议枚举

| 枚举常量 | `getValue()`（guacd 参数值） | `getDefaultPort()` |
| --- | --- | --- |
| `RDP` | `rdp` | `3389` |
| `VNC` | `vnc` | `5900` |
| `SSH` | `ssh` | `22` |
| `TELNET` | `telnet` | `23` |

- `RemoteProtocol.of(String value)`：按参数值解析，忽略大小写；不支持的值抛 `GuacamoleClientException`。
- 注意：`GuacamoleClient` 只提供 `rdp()` / `vnc()` / `ssh()` 三个快捷方法；`TELNET` 需用 `client.issue().protocol("telnet")` 或 `protocol(RemoteProtocol.TELNET)`。

### 2.8 行为细节

| 行为 | 说明 |
| --- | --- |
| token 形态 | 8 字节 `SecureRandom` → 16 位小写十六进制（`HexFormat`） |
| token 冲突 | `putIfAbsent` 失败自动重试，上限 3 次，仍冲突抛 `GuacamoleClientException("短 token 连续冲突，签发失败")` |
| 有效期 | 每个 token 包在 `ExpireValue<RemoteSpec>` 中；到期后 `getValue()` 返回 `null`，`resolve()` 相应返回 `Optional.empty()`，需重新 `issue()` |
| URL 形态 | `webUrl() + "#token=" + enc(token)`，fragment 形式，浏览器不会将 fragment 发送到服务器 |
| `contextPath` 规范化 | `webUrl()` 中：`null` 按 `/guacamole`；自动补首 `/` 与尾 `/` |
| 日志 | `issue()` 与 `refreshTokens()` 会以 `INFO` 级别输出签发 / 续期日志（slf4j） |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-guacamole-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.guacamole.support.client.GuacamoleClient;

import java.time.Duration;
import java.util.Optional;

public class Demo {
    public static void main(String[] args) {
        // 1. 创建客户端（guacd 默认本机 127.0.0.1:4822，前端默认 127.0.0.1:8080/guacamole/）
        GuacamoleClient client = GuacamoleClient.builder()
                .guacdHost("127.0.0.1").guacdPort(4822)
                .tokenTtl(Duration.ofMinutes(30))
                .build();

        // 2. 登记 RDP 连接参数，签发短 token（仅随机串，不含敏感信息）
        String token = client.rdp()
                .host("192.168.1.10")
                .port(3389)
                .username("admin")
                .password("secret")
                .param("rdp-disable-copy", "true")
                .issue();

        // 3. 生成仅含短 token 的会话 URL（fragment 形式，浏览器不会发送到服务器）
        String url = client.url(token);

        // 4. 反向解析出完整连接参数
        Optional<GuacamoleClient.RemoteSpec> spec = client.resolve(token);

        // 5.（可选）手动为全部未过期 token 续期
        client.refreshTokens();
    }
}
```

---

## 4. 配置项

**无。**

依据：本模块 `src/main/resources` 不存在（无 `application.yml` / `*.properties` / `META-INF`），源码中没有 `@ConfigurationProperties` / `@Value` 等配置绑定；所有参数（guacd 地址、Web 前端地址、上下文路径、token 有效期）均通过 `GuacamoleClient.Builder` 链式设置，不读取任何配置键。

---

## 5. SPI 扩展点

**无。**

依据：模块内只有 `GuacamoleClient` 一个具体类，没有对外抽象接口；`src/main/resources/META-INF/extensions` 等扩展声明文件不存在；common-starter 的 `ServiceProvider` 体系在本模块没有可注册的扩展点。

---

## 6. 构建与依赖

模块聚合于 `utils-support-middleware-parent`（其父为 `utils-support-parent-starter`，当前版本 `4.0.0.51`）。在仓库根目录按统一口径编译：

```bash
mvn -o -q compile -DskipTests
```

依赖关系（无第三方依赖，全部为仓库内模块）：

```text
utils-support-guacamole-starter
└── utils-support-common-starter
    ├── ExpireValue（短 token 有效期维护：of(value, ttl) / getValue() / refresh()）
    ├── Wraps（登记链扩展参数使用 Wraps.linkedMap(8)）
    └── StringUtils（token / host 空白校验）
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 说明 |
| --- | --- |
| guacd / guacamole-client 的部署与启动 | Apache Guacamole 栈（guacd 守护进程、guacamole-client WAR、数据库认证等）需自行部署；本模块只建模其地址参数 |
| guacamole 协议隧道 | 与 guacd 的指令级交互（WebSocket 隧道、sync/nop 保活、指令编解码）由前端 guacamole-common-js 与 guacd 完成，本模块不建立任何到 guacd 的网络连接 |
| 用户、权限与会话审计 | Guacamole 自身的用户体系、连接权限、操作审计不在本模块范围 |
| Spring 自动配置 / 配置绑定 | 虽名为 starter，但无 `AutoConfiguration`、无配置文件、无 SPI 扩展点，按纯 Java 库直接 `new` / `builder()` 使用 |
| token 持久化与跨实例共享 | token 登记表仅在当前进程内存中（`ConcurrentHashMap` + `ExpireValue`），不落库、不发 Redis、不跨节点共享；重启即失效 |
| `telnet()` 快捷方法 | 未提供；TELNET 用 `client.issue().protocol("telnet")` 或 `protocol(RemoteProtocol.TELNET)` 登记 |

---

## 8. 对接实战经验（附）

以下为对接真实 Guacamole 栈时的经验记录，属于集成方注意事项，不是本模块代码的行为。

### 8.1 WebSocket 隧道必须回显 guacamole 子协议

guacamole-common-js 的 `WebSocketTunnel` 以 `new WebSocket(url, "guacamole")` 发起连接，请求头携带 `Sec-WebSocket-Protocol: guacamole`。**后端 WebSocket 握手必须回显该子协议**，否则浏览器校验失败直接断开（报 `Sent non-empty 'Sec-WebSocket-Protocol' header but no response was received`）。

Spring WebSocket 后端注册端点时需声明支持的子协议：

```java
registry.addHandler(terminalHandler, "/ws/terminal")
        .setHandshakeHandler(new DefaultHandshakeHandler() {{
            setSupportedProtocols("guacamole");
        }})
        .setAllowedOriginPatterns("*");
```

> 若前端使用自定义 Tunnel（不强制子协议，如自研 `new WebSocket(url)` 无第二参数），可跳过此项；但官方 `WebSocketTunnel` 固定携带，服务端必须配合。

### 8.2 自定义 Tunnel 的保活时序（前端）

guacd 协议要求客户端对服务端 `sync` 指令回发 `sync`，否则 ~15s 判定客户端假死断连（code=1000）。官方 Client 在 `display.flush`（走 requestAnimationFrame）后才回发，页面失焦/后台标签页时 rAF 停摆会导致永远等不到回发。自定义 Tunnel 应在协议层**收到 sync/nop 立即回发**（宿主前端自研 Tunnel，如项目内 `ScGuacTunnel.ts`，即按此实现；该文件不属于本模块）。

另注意两个坑：

- 布尔值必须编码为 `"1"/"0"`（`String(true)` 会得到 `"true"`，guacd 按整数解析得 0，所有按键被当松键 → 终端无回显）；
- `Guacamole.Tunnel.INTERNAL_DATA_OPCODE` 是空字符串 `''` 而非 `"nop"`，手工拼心跳指令会得到畸形指令（`.0.;`）触发 guacd "Instruction parse error" 断连。

### 8.3 短链模式与官方 guacamole-client 的关系

本模块的 `webHost/webPort` 指向**官方 guacamole-client WAR**（或兼容其 `#token=` fragment 约定的自研前端）的地址；`url(token)` 生成 `http://webHost:webPort/guacamole/#token=xxx` 形式的会话 URL。若宿主前端自行实现 guacamole 客户端（guacamole-common-js 直连自建 WS 隧道），可不使用短链 URL，仅参考 `RemoteSpec` 的参数登记/解析语义。
