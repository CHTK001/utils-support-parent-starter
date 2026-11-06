# utils-support-tailscale-starter

Tailscale Funnel 客户端：通过子进程调用官方 `tailscale` 命令行程序，以链式 API 建立 HTTP / TCP（Funnel）隧道。

---

## 特性

- **通用层**：零 Spring 依赖，可在任意 Java 项目使用
- **子进程管理**：`create()` 自动定位 CLI → `url()`/`port()` 指定本地服务 → `start()` 建隧道
- **链式 API**：与 `CloudflareTunnelClient` 对标的单端点风格，try-with-resources 优雅关闭
- **输出消费**：独立线程持续消费 stdout 直至进程退出，避免管道满而挂起
- **面试/错误转换**：`failed to connect to local tailscaled process` 与 `please run tailscale up` 会转为 `IllegalStateException`，而非挂起
- **认证 Key 注入**：`authKey()` 写入子进程的 `TS_AUTHKEY` 环境变量（同 `tailscale up --auth-key`）
- **跨平台定位**：Windows 自动查 `C:\Program Files\Tailscale\tailscale.exe`，Linux/macOS 查 PATH（可通过 `TAILSCALE_BIN` 或 `binary()` 指定）

---

## 依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-tailscale-starter</artifactId>
    <version>4.0.0.50</version>
</dependency>
```

---

## 快速开始

### 1. HTTP 隧道（最常用）

```java
try (TailscaleTunnelClient client = TailscaleTunnelClient.create()
        .port(8080)
        .start()) {

    System.out.println("公网地址: " + client.getUrls());
}
```

自动把 `http://127.0.0.1:8080` 暴露为 `https://<machine>.<tailnet>.ts.net`。

### 2. 使用认证 Key（设备无需 `tailscale up` 登录）

```java
try (TailscaleTunnelClient client = TailscaleTunnelClient.create()
        .port(8080)
        .authKey(System.getenv("TS_AUTHKEY"))
        .start()) {

    System.out.println("公网地址: " + client.getUrls());
}
```

### 3. TCP 隧道

```java
try (TailscaleTunnelClient client = TailscaleTunnelClient.create()
        .port(8080)
        .tcpPort("443")
        .start()) {

    // 公网地址：tcp://<machine>.<tailnet>.ts.net:443
    System.out.println("TCP 地址: " + client.getUrls());
}
```

> Tailscale Funnel 只能暴露 `443 / 8443 / 10000` 三个公网 TCP 端口（未提权的设备）；生产使用的提供方实现
> 固定暴露 `443`。

### 4. 指定二进制路径

```java
Path binary = Path.of("C:\\Program Files\\Tailscale\\tailscale.exe").toAbsolutePath();
try (TailscaleTunnelClient client = TailscaleTunnelClient.create(binary)
        .port(8080)
        .start()) {
    System.out.println("公网地址: " + client.getUrls());
}
```

### 5. 获取公网地址

```java
List<String> urls = client.getUrls();
String publicUrl = client.getUrl();   // 首个公网地址
```

输出形如 `https://my-machine.my-tailnet.ts.net`（http 模式）或 `tcp://my-machine.my-tailnet.ts.net:443`（tcp 模式）。

---

## 运行环境要求

- **设备已加入 Tailnet**：`tailscale up --auth-key=TS_AUTHKEY` 认证，或本地 Tailscale 服务处于登录状态。
- **守护进程在运行**：若本地守护进程未启动，`funnel` 会在退出码 1 的同时输出
  `getting client status: failed to connect to local tailscaled process; is the Tailscale service running?`，
  本客户端会将其转为 `IllegalStateException` 交给调用方展示，而不会挂起。
- **版本要求**：Tailscale CLI >= `1.50.0`（`TailscaleTool` 自动校验，不可用时抛 `IllegalStateException` 含人工安装指引）。

---

## 输出消费

必须持续消费子进程的标准输出直至进程退出——若读到公网 URL 后立即关闭读取流，进程会因管道满而挂起。
本客户端内部使用 `tailscale-output` 标记线程持续消费，调用方无需关心。

---

## 与 spring-support-tailscale-starter 的关系

- `utils-support-tailscale-starter`（本模块） — **通用层**（CLI 定位 + 子进程管理）
- `spring-support-tailscale-starter` — **Spring Boot 集成层**（依赖本模块，封装自动配置 + `TunnelProvider` 实现）

Spring Boot 项目直接引入 `spring-support-tailscale-starter` 即可。

---

## 版本对应

| Tailscale CLI | utils-support-tailscale-starter | spring-support-tailscale-starter |
|:-------------:|:-------------------------------:|:--------------------------------:|
| 1.102.4       | 4.0.0.50                        | 4.0.0.51                         |

---

## 作者

CH <ch@chua.com>
