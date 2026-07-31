# Utils Support WebView JCEF Starter

基于 JCEF (Java Chromium Embedded Framework) 的桌面 WebView 模块，提供完整的 Chromium 内核嵌入式浏览器窗口。

## 架构

```
com.chua.webview.jcef.support
├── JcefWebviewWindow      - 浏览器窗口 (WebViewWindow 实现)
├── IpcProtocolServer       - IPC 协议服务器
├── IpcMethod               - IPC 方法定义
├── IpcMappingParser        - IPC 映射解析器
└── CefPolledDirectory      - CEF 轮询目录
```

## 特性

- **Chromium 内核**: 完整现代浏览器能力
- **IPC 通信**: Java ↔ JavaScript 双向调用
- **跨平台**: Windows / macOS / Linux
- **协议服务器**: 内置 IPC 协议服务

## 快速开始

```java
// 创建 JCEF WebView 窗口
WebViewWindow window = new JcefWebviewWindow();
window.loadUrl("https://example.com");
window.show();
```

## Maven 依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-webview-jcef-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```
