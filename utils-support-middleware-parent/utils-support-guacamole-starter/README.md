# utils-support-guacamole-starter

Guacamole 远控集成（短链模式），基于 Apache Guacamole，提供 GuacamoleClient 链式登记远控服务器参数并签发短 token，敏感参数不落地到 URL

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-guacamole-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `GuacamoleClient` | Guacamole 远控链式客户端（短链模式）。 |
| `GuacamoleClient.IssueOperation` | 远控连接参数登记链（协议、主机、端口、账号、扩展参数），`issue()` 签发短 token。 |
| `GuacamoleClient.RemoteSpec` | 远控连接参数集（不可变 record），由短 token 承载。 |
| `GuacamoleClient.RemoteProtocol` | Guacamole 支持协议：RDP/VNC/SSH/TELNET。 |
| `GuacamoleClient.GuacamoleClientException` | Guacamole 客户端异常。 |

---

## 使用示例

```java
GuacamoleClient client = GuacamoleClient.builder()
    .guacdHost("127.0.0.1").guacdPort(4822)   // guacd 默认本机
    .tokenTtl(Duration.ofMinutes(30))
    .build();

// 登记 RDP 连接参数，签发短 token（仅随机串，不含敏感信息）
String token = client.rdp()
    .host("192.168.1.10").port(3389)
    .username("admin").password("secret")
    .param("rdp-disable-copy", "true")
    .issue();

// 生成仅含短 token 的会话 URL（fragment 形式，浏览器不会发送到服务器）
String url = client.url(token);

// 反向解析出完整连接参数
Optional<GuacamoleClient.RemoteSpec> spec = client.resolve(token);

// 手动为全部未过期的 token 续期
client.refreshTokens();
```

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。guacd 服务器地址默认 `127.0.0.1:4822`，
Guacamole Web 前端默认 `127.0.0.1:8080/guacamole/`，短 token 有效期默认 1 小时（`tokenTtl` 可调）。

真实环境自检（对接真实 Guacamole 栈与真实目标机）：`com.chua.example.guacamole.GuacamoleClientLiveExample`
（测试报告：`utils-support-example-starter/docs/guacamole-client-live-test-report.html`）。

---

## 依赖关系

```
utils-support-guacamole-starter
├── utils-support-common-starter（ExpireValue 短 token 有效期维护）
```

---

## 对接实战经验（重要）

### 1. WebSocket 隧道必须回显 guacamole 子协议

guacamole-common-js 的 `WebSocketTunnel` 以 `new WebSocket(url, "guacamole")` 发起连接，
请求头携带 `Sec-WebSocket-Protocol: guacamole`。**后端 WebSocket 握手必须回显该子协议**，
否则浏览器校验失败直接断开（报 `Sent non-empty 'Sec-WebSocket-Protocol' header but no
response was received`）。

Spring WebSocket 后端注册端点时需声明支持的子协议：

```java
registry.addHandler(terminalHandler, "/ws/terminal")
        .setHandshakeHandler(new DefaultHandshakeHandler() {{
            setSupportedProtocols("guacamole");
        }})
        .setAllowedOriginPatterns("*");
```

> 若前端使用自定义 Tunnel（不强制子协议，如自研 `new WebSocket(url)` 无第二参数），
> 可跳过此项；但官方 `WebSocketTunnel` 固定携带，服务端必须配合。

### 2. 自定义 Tunnel 的保活时序（前端）

guacd 协议要求客户端对服务端 `sync` 指令回发 `sync`，否则 ~15s 判定客户端假死断连
（code=1000）。官方 Client 在 `display.flush`（走 requestAnimationFrame）后才回发，
页面失焦/后台标签页时 rAF 停摆会导致永远等不到回发。自定义 Tunnel 应在协议层**收到
sync/nop 立即回发**（参考 `ScGuacTunnel.ts` 实现）。

另注意两个坑：

- 布尔值必须编码为 `"1"/"0"`（`String(true)` 会得到 `"true"`，guacd 按整数解析得 0，
  所有按键被当松键 → 终端无回显）；
- `Guacamole.Tunnel.INTERNAL_DATA_OPCODE` 是空字符串 `''` 而非 `"nop"`，手工拼心跳
  指令会得到畸形指令（`.0.;`）触发 guacd "Instruction parse error" 断连。

### 3. 短链模式与官方 guacamole-client 的关系

本模块的 `webHost/webPort` 指向**官方 guacamole-client WAR**（或兼容其 `#token=`
fragment 约定的自研前端）的地址；`url(token)` 生成 `http://webHost:webPort/guacamole/#token=xxx`
形式的会话 URL。若宿主前端自行实现 guacamole 客户端（guacamole-common-js 直连
自建 WS 隧道），可不使用短链 URL，仅参考 `RemoteSpec` 的参数登记/解析语义。
