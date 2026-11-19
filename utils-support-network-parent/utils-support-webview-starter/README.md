# utils-support-webview-starter

> utils 体系的**桌面 WebView 窗口封装模块**：基于 `webview_java`（JNA）调用系统原生浏览器内核，把「打开一个窗口、加载一个 URL」抽象为 SPI 接口 `WebViewWindow`；并提供 `WebViewServerUtils`，把 `utils-support-common-starter` 的协议服务器（`Server`）与 WebView 窗口一键打通。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.webview.support`（子包 `com.chua.webview.support.webview`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查](#2-核心-api-与速查)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-webview-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

> 本模块 pom 未单独声明 `<version>`，跟随父模块 `utils-support-network-parent`（其父为 `utils-support-parent-starter`）的版本 `4.0.0.51`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 系统原生 WebView 窗口的 SPI 封装：Windows 用 WebView2（Edge Chromium）、macOS 用 WKWebView、Linux 用 WebKitGTK | 不是完整 Chromium 嵌入（无 DevTools、无 JS↔Java IPC，那在 `utils-support-webview-jcef-starter`） |
| 能力 | 打开窗口、加载 URL、设置标题/宽高、关闭窗口；配合 `Server` 自动起服并打开窗口 | 不提供 HTTP 服务本身、不管页面内容、不解析前端资源 |
| 依赖 | 仅 `webview_java:1.3.0`（JitPack）+ `utils-support-common-starter` | 不依赖 Spring、无自动装配（虽名为 starter，实为纯库 + SPI） |
| 配置 | 零配置，行为参数全部由 `open(...)` 入参给定 | 无配置文件、无配置键、源码不读取系统属性/环境变量 |

**判断口径**：只需「用系统自带浏览器内核弹一个窗口展示 URL」→ 选本模块；需要完整 Chromium 能力（DevTools、IPC 桥）→ 改用 `utils-support-webview-jcef-starter`。

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 直接打开一个网页窗口 | `WebViewWindow#open(url, title, width, height)` | 原生窗口，事件循环跑在独立线程 |
| 获取原生实现 | `new WebviewNativeWindow()` | 也可走 SPI：`ServiceProvider.of(WebViewWindow.class).getExtension("native")` |
| 「服务器 + 窗口」一键打开 | `WebViewServerUtils.open(window, server, title, width, height)` | 内部先 `server.startIfNeeded()`，再加载 `server.getServerUrl()` |
| 打开 IPC 协议服务器 | 同上 | 原生窗口不支持 IPC，会抛 `UnsupportedOperationException` 并提示改用 `JcefWebviewWindow` |
| 判断窗口是否支持 IPC | `WebViewWindow#supportsIpc()` | 接口默认 `false`；本模块实现不支持 |
| 关闭窗口并释放资源 | `WebViewWindow#close()` | 原生实现内部调用 `Webview#close()` |

---

## 2. 核心 API 与速查

模块共 3 个源文件，均 `@since 4.0.0.42`：接口在 `com.chua.webview.support.webview`，实现与工具在 `com.chua.webview.support`。

### 2.1 WebViewWindow（SPI 接口）

`com.chua.webview.support.webview.WebViewWindow`

| 成员 | 签名 | 说明 |
| --- | --- | --- |
| `open` | `void open(String url, String title, int width, int height)` | 打开窗口并加载指定 URL |
| `supportsIpc` | `default boolean supportsIpc()` | 是否支持进程间通信（IPC），默认 `false` |
| `close` | `void close()` | 关闭当前窗口 |

### 2.2 WebviewNativeWindow（本模块唯一实现，SPI 名 `native`）

`com.chua.webview.support.WebviewNativeWindow`，基于 `dev.webview.Webview`（webview_java，JNA）。

- 注解：`@Spi("native")`、`@SpiDescribe("原生 webview 窗口（WebView2/WKWebView/WebKitGTK）")`、`@ConditionalOnClass("dev.webview.Webview")` —— classpath 中没有 `dev.webview.Webview` 时不参与装配。
- `open(url, title, width, height)`：创建 `new Webview(true)`（源码注释：非共享模式）→ `setTitle` / `setSize` / `loadURL` → 用 `ThreadUtils.newThread(webview, "Webview Event Loop")` 启动**非守护线程**运行事件循环。内部捕获 `Throwable` 并 `log.error("Failed to open webview_java", e)`，不向上抛。
- `close()`：调用 `webview.close()`，异常降级为 `log.warn("Error closing webview", e)`。

### 2.3 WebViewServerUtils（服务器联动工具）

`com.chua.webview.support.WebViewServerUtils`，静态工具类（私有构造，防实例化）。

```java
public static void open(WebViewWindow window, Server server, String title, int width, int height)
```

执行顺序（源码逻辑）：

1. 取 `server.getProtocolType()`；
2. 若为 `ProtocolType.IPC`：窗口 `supportsIpc()` 为 `false` 时抛
   `UnsupportedOperationException("当前WebView窗口不支持IPC协议，请使用JcefWebviewWindow实现。")`；
   支持则直接返回（IPC 交互由窗口自身完成）；
3. 否则 `server.startIfNeeded()`，再 `window.open(server.getServerUrl(), title, width, height)`。

`Server` / `ProtocolType` 来自 `utils-support-common-starter`（`com.chua.common.support.network.*`）。

---

## 3. 快速开始

### 3.1 最短示例：直接打开窗口

```java
import com.chua.webview.support.WebviewNativeWindow;
import com.chua.webview.support.webview.WebViewWindow;

public class QuickStart {
    public static void main(String[] args) {
        WebViewWindow window = new WebviewNativeWindow();
        window.open("https://example.com", "WebView 演示", 1024, 768);
        // 窗口在独立的事件循环线程中运行；需要关闭时调用：
        // window.close();
    }
}
```

### 3.2 配合协议服务器：WebViewServerUtils

```java
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.webview.support.WebViewServerUtils;
import com.chua.webview.support.WebviewNativeWindow;
import com.chua.webview.support.webview.WebViewWindow;

Server server = ServerBuilder.create()
        .type("jdk")          // JDK HttpServer
        .port(8080)
        .build();

WebViewWindow window = new WebviewNativeWindow();
// 未运行则自动 start()，窗口加载 http://localhost:8080/
WebViewServerUtils.open(window, server, "控制台", 1024, 768);
```

### 3.3 通过 SPI 获取实现

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.webview.support.webview.WebViewWindow;

WebViewWindow window = ServiceProvider.of(WebViewWindow.class).getExtension("native");
```

---

## 4. 配置项

**无。**

依据：`src/main/resources` 下只有 SPI 扩展声明文件与 GraalVM native-image 配置，没有任何配置类、配置键或配置文件；三个源文件也未读取任何系统属性或环境变量。窗口的全部行为参数（URL、标题、宽、高）均由 `open(...)` 的入参给定。

---

## 5. SPI 扩展点

唯一扩展点：`WebViewWindow`。

本模块的注册文件 `src/main/resources/META-INF/extensions/com.chua.webview.support.webview.WebViewWindow`：

```properties
native=com.chua.webview.support.WebviewNativeWindow
```

自定义实现步骤：

```java
@Spi("my-window")                    // SPI 名称
@SpiDescribe("自定义窗口")             // 可选描述
public class MyWebViewWindow implements WebViewWindow {
    @Override public void open(String url, String title, int width, int height) { /* ... */ }
    @Override public void close() { /* ... */ }
    // supportsIpc() 按需覆写，默认 false
}
```

并在自己模块的 `src/main/resources/META-INF/extensions/com.chua.webview.support.webview.WebViewWindow` 中追加：

```properties
my-window=com.example.MyWebViewWindow
```

之后即可 `ServiceProvider.of(WebViewWindow.class).getExtension("my-window")` 获取。
`@Spi` / `@SpiDescribe` / `@ConditionalOnClass` / `ServiceProvider` 均由 `utils-support-common-starter` 提供。

---

## 6. 构建与依赖

```text
父链：utils-support-parent-starter (4.0.0.51)
        └── utils-support-network-parent (4.0.0.51)
              └── utils-support-webview-starter (4.0.0.51)
```

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.github.webview:webview_java` | `1.3.0` | JNA 封装的系统原生 WebView（WebView2 / WKWebView / WebKitGTK），经 JitPack 仓库（`https://jitpack.io`）获取 |
| `com.chua:utils-support-common-starter` | 随父版本管理 | `Server` / `ProtocolType` / SPI（`ServiceProvider`、`@Spi`、`@ConditionalOnClass`）/ `ThreadUtils` 等契约与工具 |

编译：JDK `25`（`maven.compiler.source/target=25`），源码编码 UTF-8。

GraalVM native-image 配置（`src/main/resources/META-INF/native-image/com.chua/utils-support-webview-starter/`）：

| 文件 | 内容 |
| --- | --- |
| `resource-config.json` | 包含 `META-INF/services/.*` |
| `proxy-config.json` | 为 `com.chua.webview.support.webview.WebViewWindow` 注册动态代理 |
| `reflect-config.json` | 反射配置 |

平台运行时要求（来自源码 javadoc 与 pom 描述）：

| 平台 | 内核 | 要求 |
| --- | --- | --- |
| Windows | WebView2（Edge Chromium） | 需系统提供 WebView2 Runtime |
| macOS | WKWebView | 系统自带 |
| Linux | WebKitGTK | 需安装 webkit2gtk |

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| 完整 Chromium 内核、DevTools、JS↔Java IPC 桥 | `utils-support-webview-jcef-starter`：`JcefWebviewWindow`（`supportsIpc()` 返回 `true`，基于 `jcefmaven:135.0.20`）；`WebViewServerUtils` 遇 IPC 服务器时提示的正是该实现 |
| `Server` / `ServerBuilder` / `ProtocolType` / `ServiceProvider` 等网络与 SPI 契约 | `utils-support-common-starter` |
| HTTP/TCP 等协议服务器的具体实现 | `utils-support-common-starter`（jdk/nio/aio 等内置）及各协议 starter |
| Spring 自动装配 | 本模块没有（`resources` 下无 `spring.factories` / `AutoConfiguration.imports`） |
| 页面内容（HTML / 前端资源）与业务逻辑 | 不归本模块；本模块只负责「把窗口弹出来并加载 URL」 |
