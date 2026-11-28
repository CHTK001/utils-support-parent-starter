# utils-support-network-parent

远程连接、HTTP/RPC、浏览器、隧道和协议服务器的实现集合。基础 Server/Client/SPI 契约位于 [common](../utils-support-core-parent/utils-support-common-starter/README.md)。版本 `4.0.0.51`、JDK 25；父 POM 不启动监听服务。

## 模块目录

| 模块 README | 主要能力 |
|---|---|
| [ssh](utils-support-ssh-starter/README.md) | SSH/SFTP 客户端、Shell 服务端、隧道、远程服务与目录轮询 |
| [winrm](utils-support-winrm-starter/README.md) | WinRM 执行、文件与远程服务 |
| [ftp](utils-support-ftp-starter/README.md) | FTP/FTPS 客户端及轮询 |
| [smb](utils-support-smb-starter/README.md) | SMB 客户端、Rust 服务端及 FileStorage |
| [webdav](utils-support-webdav-starter/README.md) | WebDAV FileStorage 与配置加载 |
| [symmetric](utils-support-symmetric-starter/README.md) | SymmetricDS 数据库同步轮询 |
| [httpclient](utils-support-httpclient-starter/README.md) | Apache HttpClient5 执行器 |
| [okhttp](utils-support-okhttp-starter/README.md) | OkHttp HTTP/SSE 实现 |
| [retrofit](utils-support-retrofit-starter/README.md) | Retrofit 声明式 HTTP 调用 |
| [rpc-sofa](utils-support-rpc-sofa-starter/README.md) | SOFA RPC |
| [rpc-json](utils-support-rpc-json-starter/README.md) | JSON-RPC 2.0 |
| [rpc-dubbo](utils-support-rpc-dubbo-starter/README.md) | Dubbo 服务/客户端与指标 |
| [rpc-zmq](utils-support-rpc-zmq-starter/README.md) | ZeroMQ RPC/Invoker/分发器 |
| [socketio](utils-support-socketio-starter/README.md) | Socket.IO 服务端、Sync 与 DataSync 通道 |
| [rsocket](utils-support-rsocket-starter/README.md) | RSocket TCP 响应式同步与分发 |
| [kcp](utils-support-kcp-starter/README.md) | KCP 消息、请求响应和同步通道 |
| [armeria](utils-support-armeria-starter/README.md) | Armeria HTTP Server |
| [quarkus](utils-support-quarkus-starter/README.md) | Vert.x HTTP 与 JAX-RS/CDI 桥接 |
| [modbus](utils-support-modbus-starter/README.md) | TCP/UDP/RTU/ASCII 主站、从站 |
| [dht](utils-support-dht-starter/README.md) | Kademlia、KRPC、资源发现及 BEP9 元数据 |
| [kitesurf](utils-support-kitesurf-starter/README.md) | 远程 URL 渲染、PDF、截图与 MCP |
| [playwright](utils-support-playwright-starter/README.md) | 浏览器引擎、抓取/截图、网页对话与业务压测 |
| [jvppeteer](utils-support-jvppeteer-starter/README.md) | Jvppeteer 浏览器、截图、抓取和网页对话 |
| [cdp](utils-support-cdp-starter/README.md) | JDK CDP 引擎的抓取/解析适配 |
| [duckduckgo](utils-support-duckduckgo-starter/README.md) | Duck.ai 浏览器对话通道 |
| [kimi](utils-support-kimi-starter/README.md) | Kimi 网页会话协议客户端 |
| [ngrok](utils-support-ngrok-starter/README.md) | ngrok Agent SDK 隧道 |
| [cloudflared](utils-support-cloudflared-starter/README.md) | cloudflared 子进程与 Quick/命名隧道 |
| [tailscale](utils-support-tailscale-starter/README.md) | Tailscale Funnel 进程管理 |
| [git](utils-support-git-starter/README.md) | JGit 链式客户端、监听和部署编排 |
| [acme](utils-support-acme-starter/README.md) | ACME 账户、挑战、签发/续签/吊销 |
| [tshark](utils-support-tshark-starter/README.md) | 实时/离线抓包、流重组和会话聚合 |
| [webview](utils-support-webview-starter/README.md) | 系统内核桌面 WebView |
| [webview-jcef](utils-support-webview-jcef-starter/README.md) | JCEF Chromium 与 JS/Java IPC |
| [sinch](utils-support-sinch-starter/README.md) | 语音 SPI 骨架，呼叫/查询/挂断尚未实现 |
| [jrebel](utils-support-jrebel-starter/README.md) | 现有 JRebel 协议服务器实现 |

## 安装与运行条件

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ssh-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

按实际需求选择客户端或服务端 SPI，端口/认证/超时显式配置，资源用完关闭。浏览器模块需要可运行的浏览器，隧道/抓包模块需要相应程序和系统权限；这些要求见各 README，依赖安装不等于外部运行环境就绪。

network 的 playwright 与 [extra 同名目录](../utils-support-extra-parent/utils-support-playwright-starter/README.md) 使用相同 artifactId，但实现不同；默认 reactor 仅登记本目录这份。

仓库根构建：`mvn -pl utils-support-network-parent -am compile -DskipTests`。返回 [完整目录](../README.md)。
