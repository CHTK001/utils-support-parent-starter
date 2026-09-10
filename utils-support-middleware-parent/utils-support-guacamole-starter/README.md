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

---

## 依赖关系

```
utils-support-guacamole-starter
├── utils-support-common-starter（ExpireValue 短 token 有效期维护）
```
