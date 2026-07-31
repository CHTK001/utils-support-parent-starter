# Utils Support WebView Starter

基于 webview_java 的桌面 WebView 模块，提供系统原生嵌入式浏览器窗口。

---

## 功能概览

| 功能 | 说明 |
|------|------|
| 系统原生 WebView | Windows WebView2 / macOS WKWebView / Linux WebKitGTK |
| 轻量级 | 无捆绑 Chromium，使用系统自带浏览器引擎 |
| 跨平台 | Windows / macOS / Linux |
| URL 加载 | 加载网页 URL 或本地 HTML |
| 窗口管理 | 创建、显示、关闭 WebView 窗口 |

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-webview-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 创建 WebView 窗口

```java
import com.chua.webview.support.WebviewNativeWindow;
import com.chua.webview.support.WebViewWindow;

// 创建原生 WebView 窗口
WebViewWindow window = new WebviewNativeWindow();
window.loadUrl("https://example.com");
window.show();
```

---

## 架构

```
com.chua.webview.support
├── WebviewNativeWindow     # 原生窗口管理
├── WebViewWindow           # WebView 窗口接口
└── WebViewServerUtils      # 服务端工具
```

---

## 平台支持

| 平台 | 引擎 | 说明 |
|------|------|------|
| Windows | WebView2 | 需要 Edge WebView2 Runtime |
| macOS | WKWebView | 系统自带 |
| Linux | WebKitGTK | 需要安装 webkit2gtk |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-webview-starter
├── utils-support-common-starter  # 核心基础
└── io.github.nicewarm:webview_java  # WebView 桥接库
```

---

## 注意事项

- Windows 需要安装 Edge WebView2 Runtime（Windows 10/11 自带）
- Linux 需要安装 `libwebkit2gtk-4.0-dev`
- 本模块使用系统原生引擎，体积小但功能相对基础
- 如需完整 Chromium 能力，使用 `utils-support-webview-jcef-starter`
