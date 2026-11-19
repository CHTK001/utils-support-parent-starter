# utils-support-webview-jcef-starter

> utils 体系的 **JCEF WebView 集成模块**：基于 [jcefmaven](https://github.com/jcefmaven/jcefmaven)（JCEF，Java Chromium Embedded Framework）提供桌面内嵌 Chromium 浏览器窗口，并通过 CEF Message Router 打通 JS ↔ Java 的 IPC 通道。相比平台专属的 WebView2 / WKWebView / WebKitGTK，JCEF 在 Windows / macOS / Linux 上提供统一的 Chromium 内核。
>
> 版本：`4.0.0.51`｜JDK：`25`（随父 POM `maven.compiler.source/target=25`）｜包名：`com.chua.webview.jcef.support`（含子包 `.internal`）

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 JcefWebviewWindow](#21-jcefwebviewwindow)
  - [2.2 常用能力速查](#22-常用能力速查)
  - [2.3 internal 包：协议服务器契约](#23-internal-包协议服务器契约)
  - [2.4 IPC 消息流转](#24-ipc-消息流转)
- [3. 快速开始](#3-快速开始)
  - [3.1 打开一个网页窗口](#31-打开一个网页窗口)
  - [3.2 通过 SPI 获取实现](#32-通过-spi-获取实现)
  - [3.3 IPC 模式：JS 调 Java](#33-ipc-模式js-调-java)
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
    <artifactId>utils-support-webview-jcef-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

- 隶属：`utils-support-parent-starter` → `utils-support-network-parent`
- 模块名：`Utils Support WebView JCEF Starter`，描述「JCEF WebView IPC 集成」
- 规模：5 个 Java 源文件（1 个公开实现类 + 4 个 `internal` 契约类型）
- API 起源：`@since 4.0.0.42`（`JcefWebviewWindow` 及 internal 契约）

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | JCEF（Chromium 内核）桌面 webview 窗口的 SPI 实现 + JS↔Java IPC 通道 | 不是浏览器自动化 / 测试框架 |
| 抽象 | `com.chua.webview.support.webview.WebViewWindow` 的实现类，`supportsIpc()` 恒为 `true` | 不定义 webview 抽象（接口在 `utils-support-webview-starter`） |
| 依赖 | 仅 `jcefmaven` + 两个 `provided` 的同仓契约模块 | 不依赖 Spring；不带 HTTP 服务端；不管 IPC 业务路由 |
| 平台 | AWT `Frame` 承载，Windows / macOS / Linux 由 JCEF 支持 | 不是 Android / iOS webview |

---

## 2. 核心 API 与速查表

### 2.1 JcefWebviewWindow

`com.chua.webview.jcef.support.JcefWebviewWindow`，实现 `WebViewWindow`，经 `META-INF/services` 注册为 SPI 实现。

| 方法 | 说明 |
| --- | --- |
| `open(String url, String title, int width, int height)` | 用 `CefAppBuilder` 构建 `CefApp`，创建 `CefClient` 与 `CefBrowser`，装入 AWT `Frame` 并显示；窗口关闭事件回调 `close()` |
| `open(ProtocolServer server, String title, int width, int height)` | 按协议类型分派：`ProtocolType.IPC` 走内部 `openIpc`（CEF Message Router + 内置 data: 页面），打开前先调 `server.startIfNeeded()`；其余类型取 `server.getServerUrl()` 当作普通网页地址打开 |
| `supportsIpc()` | 恒返回 `true` |
| `close()` | `browser.close(true)` + `frame.dispose()`，并置空 `client` / `ipcServer` |

注意：

- `open` 系列方法内部捕获 `Throwable` 并记录 error 日志（`Failed to open JCEF webview` / `Failed to open JCEF IPC webview`），不会向上抛出；使用前需确保 JCEF 原生库可用（由 jcefmaven 提供）。
- IPC 方式要求传入的 `server` 是 `IpcProtocolServer` 实例，否则记录 error 日志 `IPC protocol requires IpcProtocolServer, got: ...` 并直接返回。
- IPC 窗口加载的是内置页面（`data:text/html;base64,...`），标题为 `JCEF IPC Bridge`，提示 `IPC protocol server is ready`。

### 2.2 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 打开网页窗口 | `JcefWebviewWindow#open(String url, String title, int width, int height)` | AWT `Frame` + `CefBrowser` |
| 通过 SPI 获取实现 | `ServiceLoader.load(WebViewWindow.class)` | 注册文件 `META-INF/services/com.chua.webview.support.webview.WebViewWindow` |
| 判断是否支持 IPC | `JcefWebviewWindow#supportsIpc()` | 恒返回 `true` |
| 以 IPC 方式打开窗口 | `JcefWebviewWindow#open(ProtocolServer, String title, int width, int height)` | `ProtocolType.IPC` 走 CEF Message Router，其余按 `getServerUrl()` 直接打开 |
| 处理 JS → Java 消息 | 继承 `IpcProtocolServer` 实现 `handleMessage(String source, String path, String body)` | JCEF 侧固定传 `source = "jcef-ipc"` |
| 自定义协议服务器 | 实现 `ProtocolServer`（`getProtocolType` / `getServerUrl` / `startIfNeeded` / `shutdown`） | 非 IPC 类型按 URL 直接打开 |
| 协议类型枚举 | `ProtocolType`：`HTTP` / `HTTPS` / `IPC` / `WEBSOCKET` / `TCP` / `UDP` | `IpcProtocolServer` 恒为 `IPC`，`getServerUrl()` 恒为 `ipc://local` |
| 关闭窗口 | `JcefWebviewWindow#close()` | 释放 browser / frame，置空 client / ipcServer |

### 2.3 internal 包：协议服务器契约

`com.chua.webview.jcef.support.internal` 下的 4 个类型（`@since 4.0.0.42`）：

| 类型 | 形态 | 成员 | 说明 |
| --- | --- | --- | --- |
| `ProtocolServer` | 接口 | `getProtocolType()`、`getServerUrl()`、`startIfNeeded()`、`shutdown()` | 协议服务器契约 |
| `IpcProtocolServer` | 抽象类，实现 `ProtocolServer` | `getProtocolType()` 恒 `IPC`；`getServerUrl()` 恒 `ipc://local`；`startIfNeeded()` / `shutdown()` 为空实现（子类可覆盖）；抽象方法 `handleMessage(String source, String path, String body)` | IPC 服务端基类，业务在 `handleMessage` |
| `ProtocolType` | 枚举 | `HTTP`、`HTTPS`、`IPC`、`WEBSOCKET`、`TCP`、`UDP` | 协议类型 |
| `MappingDefinition` | 接口 | `getName()`、`getMethod()`（返回 `String[]`）、`getPath()` | 映射定义契约；当前模块内无其他类引用（源码内无实现或调用） |

### 2.4 IPC 消息流转

`openIpc` 用 `CefMessageRouterConfig("javaBridge", "javaBridgeCancel")` 注册消息路由，并挂载 `IpcMessageRouterHandler`（继承 `CefMessageRouterHandlerAdapter`）。整体流转：

```text
JS: window.javaBridge.send(JSON.stringify({path, method, body}), callback)
        │  CEF Message Router（路由名 javaBridge，取消名 javaBridgeCancel）
        ▼
IpcMessageRouterHandler.onQuery(...)
        │  用 com.chua.common.support.lang.json.Json 解析：
        │  path（默认 "/"）、method（默认 "POST"）、body（默认 ""）
        ▼
IpcProtocolServer.handleMessage("jcef-ipc", path, body)
        │  返回响应字符串
        ▼
callback.success(response)        // 异常时: callback.failure(-1, e.getMessage())
```

---

## 3. 快速开始

### 3.1 打开一个网页窗口

```java
import com.chua.webview.jcef.support.JcefWebviewWindow;

public class Demo {
    public static void main(String[] args) {
        JcefWebviewWindow window = new JcefWebviewWindow();
        window.open("https://example.com", "示例窗口", 1280, 800);

        // 用户点击窗口关闭按钮时，框架自动回调 close()；
        // 也可以主动调用：window.close();
    }
}
```

### 3.2 通过 SPI 获取实现

模块通过标准 Java SPI（`META-INF/services`）注册，宿主可面向接口使用：

```java
import com.chua.webview.support.webview.WebViewWindow;

import java.util.ServiceLoader;

WebViewWindow window = ServiceLoader.load(WebViewWindow.class)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("未找到 WebViewWindow 实现"));

window.open("https://example.com", "示例窗口", 1280, 800);
```

### 3.3 IPC 模式：JS 调 Java

Java 侧：继承 `IpcProtocolServer`，实现 `handleMessage`，然后以 IPC 方式打开窗口：

```java
import com.chua.webview.jcef.support.JcefWebviewWindow;
import com.chua.webview.jcef.support.internal.IpcProtocolServer;

public class IpcDemo {
    public static void main(String[] args) {
        IpcProtocolServer server = new IpcProtocolServer() {
            @Override
            public String handleMessage(String source, String path, String body) {
                if ("/hello".equals(path)) {
                    return "{\"code\":0,\"data\":\"hello from java\"}";
                }
                return "{\"code\":404}";
            }
        };

        JcefWebviewWindow window = new JcefWebviewWindow();
        window.open(server, "IPC 示例", 1280, 800);
    }
}
```

JS 侧（页面内）按路由名 `javaBridge` 发送消息：

```js
window.javaBridge.send(
    JSON.stringify({ path: '/hello', method: 'POST', body: '' }),
    function (response) { console.log('java 返回:', response); }
);
```

---

## 4. 配置项

**无。** 依据：模块源码与资源中不存在任何配置文件或配置键——没有 `application.yml` / `*.properties` / `spring.factories`，也没有配置类；唯一随包资源是 SPI 注册文件与 GraalVM native-image 元数据（见 [6. 构建与依赖](#6-构建与依赖)）。运行期行为只由方法入参（url / title / width / height / ProtocolServer）决定；jcefmaven 自身的可调参数不在本模块源码范围内（源码未提供）。

---

## 5. SPI 扩展点

本模块在 SPI 体系中是**实现方**，同时向宿主开放两类扩展点：

**1）本模块注册的 SPI 实现**

- `META-INF/services/com.chua.webview.support.webview.WebViewWindow` → `com.chua.webview.jcef.support.JcefWebviewWindow`
- 宿主通过 JDK `ServiceLoader` 发现；classpath 缺少 `org.cef.CefApp`（即未引入本模块）时自然不会命中该实现。

**2）宿主可实现的扩展点**

| 扩展点 | 方式 | 说明 |
| --- | --- | --- |
| IPC 业务处理 | 继承 `IpcProtocolServer`，实现 `handleMessage(String source, String path, String body)` | 唯一抽象方法；返回值即回传给 JS 的响应字符串 |
| IPC 生命周期 | 覆盖 `IpcProtocolServer#startIfNeeded()` / `#shutdown()` | 默认空实现；IPC 路径打开窗口前会调 `startIfNeeded()` |
| 自定义协议服务器 | 实现 `ProtocolServer` 接口 | `open(ProtocolServer, ...)` 按 `getProtocolType()` 分派：`IPC` 走消息路由；`HTTP` / `HTTPS` / `WEBSOCKET` / `TCP` / `UDP` 等其余类型用 `getServerUrl()` 作为网页地址直接打开 |

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（根 POM `utils-support-parent-starter` 的 `maven.compiler.source/target=25`）。
- 源码 UTF-8、无 BOM；使用 Lombok（`@Slf4j`）。

```bash
# 在 utils-support-parent-starter 仓库根目录
mvn -pl utils-support-network-parent/utils-support-webview-jcef-starter -am clean package
```

### 6.2 依赖清单（本模块 pom.xml）

| 依赖 | 版本 | Scope | 用途 |
| --- | --- | --- | --- |
| `me.friwi:jcefmaven` | `135.0.20`（属性 `jcef.version`） | compile | JCEF 封装：`CefAppBuilder` / `CefApp` / `CefClient` / `CefBrowser` / `CefMessageRouter` |
| `com.chua:utils-support-network-starter` | `${project.version}` = `4.0.0.51` | provided | 同仓网络基础模块（编译期依赖） |
| `com.chua:utils-support-webview-starter` | `${project.version}` = `4.0.0.51` | provided | `WebViewWindow` SPI 接口定义 |

> 代码中 IPC 消息解析使用 `com.chua.common.support.lang.json.Json`，该能力经由上述 provided 依赖间接获得。

### 6.3 GraalVM native-image 元数据

`src/main/resources/META-INF/native-image/com.chua/utils-support-webview-jcef-starter/`：

| 文件 | 内容 |
| --- | --- |
| `reflect-config.json` | `JcefWebviewWindow` 的全部构造器 / 方法 / 字段开放反射 |
| `resource-config.json` | 保留 `META-INF/services/.*` 与 `META-INF/extensions/.*` 资源 |
| `proxy-config.json` | 登记接口代理：`com.chua.common.support.directory.PolledDirectory`、`com.chua.common.support.webview.WebViewWindow`（以文件内容为准） |

---

## 7. 边界：这些能力不在本模块

| 能力 | 去处 / 说明 |
| --- | --- |
| `WebViewWindow` 抽象与 SPI 定义 | 在 `utils-support-webview-starter`；本模块只是其 JCEF 实现 |
| 浏览器自动化（页面点击、截图、Cookie/网络拦截等） | 在同父模块的 `utils-support-cdp-starter` / `utils-support-playwright-starter` / `utils-support-jvppeteer-starter` |
| HTTP 客户端 / 服务端 | 在 `utils-support-common-starter` 的 `network` 包及 `utils-support-httpclient-starter` / `utils-support-okhttp-starter` |
| IPC 业务逻辑（路由表、鉴权、返回值语义） | 由宿主应用在 `IpcProtocolServer#handleMessage` 中自行实现；本模块只提供 JS↔Java 通道 |
| Spring Boot 自动装配 | 无。本模块不依赖 Spring，也无 `spring.factories` / `AutoConfiguration.imports` |
| JCEF 原生库的下载与安装策略 | 由 jcefmaven 自身决定；本模块源码未提供额外配置 |

---

## 许可证

Apache License 2.0
