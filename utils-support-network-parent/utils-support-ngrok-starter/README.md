# utils-support-ngrok-starter

Ngrok 内嵌客户端，基于官方 [ngrok-java](https://github.com/ngrok/ngrok-java) Agent SDK，提供链式 API 启动 HTTP / TCP / TLS 隧道。

## 特性

- **零外部进程**：内嵌 Rust Agent，无需启动独立 `ngrok` CLI
- **跨平台**：Windows / macOS / Linux（x86_64、aarch64、armv7、i686），通过 `os-maven-plugin` 自动选择 native 分类器
- **链式 API**：`create(token).connect().http().domain(...).listenHttp()` 一行完成隧道建立
- **支持三种端点**：HTTP / HTTPS（HttpBuilder）、TCP（TcpBuilder）、TLS（TlsBuilder）
- **支持两种模式**：`listen()` 自管连接 / `forward(url)` 自动转发到内部 URL
- **私有部署**：可通过 `serverAddr()` / `caCert()` 对接自建 ngrok 服务端
- **生命周期安全**：实现 `AutoCloseable`，try-with-resources 即可优雅关闭

## 依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ngrok-starter</artifactId>
    <version>4.0.0.42</version>
</dependency>
```

## 快速开始

### 1. HTTP 监听 + 转发到本地 8080

```java
try (NgrokClient client = NgrokClient.create(System.getenv("NGROK_AUTHTOKEN"))
        .metadata("my-app")
        .connect()
        .http()
        .domain("example.ngrok-free.app")
        .forwardHttp(new URL("http://127.0.0.1:8080"))) {

    System.out.println("公网 URL: " + client.getUrls());
    Thread.currentThread().join();
}
```

### 2. TCP 隧道

```java
try (NgrokClient client = NgrokClient.create(token)
        .connect()
        .tcp()
        .remoteAddress("1.tcp.ngrok.io:20000")
        .listenTcp()
        .block()) {

    // 任何 TCP 客户端可连接 1.tcp.ngrok.io:20000
    System.out.println("TCP 公网地址: " + client.getUrls());
}
```

### 3. 同时启动多个隧道

```java
try (NgrokClient client = NgrokClient.create(token)
        .connect()
        .http()
        .domain("a.example.ngrok-free.app")
        .forwardHttp(new URL("http://127.0.0.1:8080"))
        .tcp()
        .remoteAddress("1.tcp.ngrok.io:20000")
        .listenTcp()
        .block()) {

    System.out.println("所有公网 URL: " + client.getUrls());
}
```

### 4. 私有部署

```java
byte[] caCert = Files.readAllBytes(Paths.get("/path/to/ca.pem"));

try (NgrokClient client = NgrokClient.create(token)
        .serverAddr("tunnel.example.com:443")
        .caCert(caCert)
        .connect()
        .http()
        .domain("internal.example.com")
        .forwardHttp(new URL("http://127.0.0.1:8080"))
        .block()) {
    // ...
}
```

### 5. HTTP 鉴权（Basic / OAuth / OIDC）

```java
try (NgrokClient client = NgrokClient.create(token)
        .connect()
        .http()
        .domain("auth.example.ngrok-free.app")
        .basicAuthOptions(new com.ngrok.Http.BasicAuth("admin", "secret"))
        .forwardHttp(new URL("http://127.0.0.1:8080"))
        .block()) {
    // ...
}
```

## 链式 API 总览

```
NgrokClient
  ├── .create(token)            // 静态工厂
  ├── .metadata(String)         // 会话元数据
  ├── .serverAddr(String)       // 私有部署服务端地址
  ├── .caCert(byte[])           // 私有部署 CA 证书
  ├── .heartbeatInterval(Duration)
  ├── .heartbeatTolerance(Duration)
  ├── .connect()                // 建立 Session
  │
  ├── .http()                   // 准备 HTTP 端点
  │     ├── .metadata(String)
  │     ├── .forwardsTo(String)
  │     ├── .domain(String)
  │     ├── .scheme(Http.Scheme)
  │     ├── .compression()
  │     ├── .circuitBreaker(double)
  │     ├── .basicAuthOptions(Http.BasicAuth)
  │     ├── .oauthOptions(Http.OAuth)
  │     ├── .oidcOptions(Http.OIDC)
  │     ├── .webhookVerification(Http.WebhookVerification)
  │     ├── .addRequestHeader(name, value) / .addResponseHeader(...)
  │     ├── .removeRequestHeader(name) / .removeResponseHeader(...)
  │     ├── .allowCIDR(cidr) / .denyCIDR(cidr)
  │     ├── .trafficPolicy(yaml)
  │     ├── .listenHttp()       // 启动 HTTP 监听
  │     └── .forwardHttp(URL)   // 启动 HTTP 转发
  │
  ├── .tcp()                    // 准备 TCP 端点
  │     ├── .metadata(String)
  │     ├── .forwardsTo(String)
  │     ├── .remoteAddress(String)
  │     ├── .allowCIDR(cidr) / .denyCIDR(cidr)
  │     ├── .listenTcp()
  │     └── .forwardTcp(URL)
  │
  ├── .tls()                    // 准备 TLS 端点
  │     ├── .metadata(String)
  │     ├── .forwardsTo(String)
  │     ├── .domain(String)
  │     ├── .allowCIDR(cidr) / .denyCIDR(cidr)
  │     ├── .listenTls()
  │     └── .forwardTls(URL)
  │
  ├── .block()                  // 阻塞当前线程
  ├── .getUrls()                // 获取所有公网 URL
  ├── .getSession()             // 获取底层 Session
  └── .close()                  // 关闭（AutoCloseable）
```

## 环境要求

- **Java 11+**（本仓库统一使用 Java 25）
- **ngrok authtoken**：注册 <https://dashboard.ngrok.com> 获取
- **网络**：需能访问 `connect.ngrok-agent.com:443`（私有部署时改为自建地址）

## 版本对应

| ngrok-java | 本模块 |
|:----------:|:------:|
| 1.1.1      | 4.0.0.42 |

## 常见问题

- **`UnsatisfiedLinkError`**：通常表示 `ngrok-java-native` 分类器与平台不匹配，确认 `os-maven-plugin` 已启用
- **认证失败**：检查 authtoken 是否正确；使用 `Session.withAuthtokenFromEnv()` 可读取 `NGROK_AUTHTOKEN` 环境变量
- **域名绑定失败**：自定义域名需先在 <https://dashboard.ngrok.com/cloud-edge/domains> 注册并设置 CNAME

## 作者

CH <ch@chua.com>
