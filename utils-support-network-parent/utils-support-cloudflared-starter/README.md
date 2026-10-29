# utils-support-cloudflared-starter

Cloudflare Tunnel 内嵌客户端：管理官方 `cloudflared` 守护进程，提供链式 API 启动 Quick Tunnel（免账号）或远程管理命名隧道。

## 特性

- **免账号 Quick Tunnel**：无需登录、无需 token，一条命令把本地端口暴露为 `*.trycloudflare.com` 公网地址
- **远程管理命名隧道**：支持 Zero Trust 面板生成的 Token，可固定自定义域名
- **零外部进程依赖**：直接内嵌 `cloudflared` 进程，复用 `CliTool` 自动定位（`CLOUDFLARED_BIN` 环境变量 / PATH / 常见安装目录）
- **链式 API**：`create().url(...).start()` 一行完成隧道建立
- **URL 自动解析**：从进程输出中提取公网地址，无需手工读取日志
- **生命周期安全**：实现 `AutoCloseable`，优雅关闭，超时强杀，不留孤儿进程
- **凭据保护**：日志输出自动脱敏 token

## 依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-cloudflared-starter</artifactId>
    <version>4.0.0.42</version>
</dependency>
```

需要先安装 [cloudflared](https://github.com/cloudflare/cloudflared/releases)：
- macOS：`brew install cloudflared`
- Windows：`winget install Cloudflare.cloudflared` 或下载可执行文件
- Linux：下载二进制后放到 PATH

## 快速开始

### 1. Quick Tunnel（免账号，最常用）

```java
try (CloudflareTunnelClient client = CloudflareTunnelClient.create()
        .url("http://127.0.0.1:8080")
        .start()) {

    System.out.println("公网 URL: " + client.getUrls());
    Thread.currentThread().join();
}
```

输出示例：

```
公网 URL: [https://xxx-xxx-xxx.trycloudflare.com]
```

### 2. 远程管理命名隧道（Token）

```java
try (CloudflareTunnelClient client = CloudflareTunnelClient.create()
        .token(System.getenv("CLOUDFLARE_TUNNEL_TOKEN"))
        .url("http://127.0.0.1:8080")
        .start()) {

    System.out.println("公网 URL: " + client.getUrls());
}
```

Token 在 [Zero Trust 面板](https://one.dash.cloudflare.com) → Networks → Tunnels → Create tunnel 中生成。

### 3. 固定自定义域名

```java
try (CloudflareTunnelClient client = CloudflareTunnelClient.create()
        .url("http://127.0.0.1:8080")
        .hostname("tunnel.example.com")
        .start()) {

    System.out.println("公网 URL: " + client.getUrls());
}
```

前提：该域名已添加到 Cloudflare 账户并配置 CNAME 路由。

### 4. 指定本地端口（便捷写法）

```java
try (CloudflareTunnelClient client = CloudflareTunnelClient.create()
        .port(8080)
        .start()) {

    System.out.println("公网 URL: " + client.getUrls());
}
```

### 5. 阻塞保持运行

```java
try (CloudflareTunnelClient client = CloudflareTunnelClient.create()
        .url("http://127.0.0.1:8080")
        .start()
        .block()) {
    // 阻塞直到进程退出
}
```

## 链式 API 总览

```
CloudflareTunnelClient
  ├── .create()                    // 静态工厂：自动定位 cloudflared，找不到抛错
  ├── .create(Path binary)         // 静态工厂：指定可执行文件路径
  ├── .binary(Path)                // 指定可执行文件路径
  ├── .token(String)               // 远程管理隧道 Token（可选）
  ├── .url(String)                 // 本地服务地址，如 http://127.0.0.1:8080
  ├── .port(int)                   // 便捷：http://127.0.0.1:<port>
  ├── .hostname(String)            // 固定自定义域名（可选）
  ├── .noAutoUpdate(boolean)       // 关闭自动更新（默认 true）
  ├── .logLevel(String)            // 日志级别（默认 info）
  ├── .startTimeout(Duration)      // 启动等待超时（默认 40s）
  ├── .start()                     // 启动隧道，等待公网 URL
  ├── .block()                     // 阻塞当前线程
  ├── .getUrls() / .getUrl()       // 获取公网 URL
  ├── .isRunning()                 // 进程是否存活
  ├── .getProcess() / .getExitCode()
  └── .close()                     // 关闭（AutoCloseable）
```

## 环境要求

- **Java 8+**（本仓库统一 Java 25）
- **cloudflared**：Quick Tunnel 免账号；命名隧道需 Zero Trust 账户与 Token
- **网络**：需能访问 Cloudflare 边缘网络（TCP 443）

## 常见问题

- **`未找到 cloudflared`**：设置 `CLOUDFLARED_BIN` 环境变量指向可执行文件，或安装后重试
- **`进程提前退出`**：检查 token 是否有效、本地服务是否可达（`--url` 指向的服务必须已启动）
- **`启动超时`**：通常为网络问题（无法访问 Cloudflare 边缘）；固定域名场景需确认 DNS CNAME 已生效
- **隧道变 530**：多因外部关闭了进程的标准输出流——本客户端持续消费输出直至进程退出，无此问题

## 作者

CH <ch@chua.com>