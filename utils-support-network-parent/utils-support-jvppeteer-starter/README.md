# utils-support-jvppeteer-starter

Jvppeteer 浏览器自动化：URL 截图、长截图、页面加载等待 + 爬虫浏览器渲染抓取/解析 + 浏览器反代对话客户端

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-jvppeteer-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `JvppeteerScreenshot` | Jvppeteer 截图工具，支持视口截图、整页长截图、等待选择器、时区/语言/请求头配置。 |
| `JvppeteerFetcher` | Jvppeteer 浏览器渲染抓取器。 (SPI: `jvppeteer`) |
| `JvppeteerParser` | Jvppeteer 浏览器渲染解析器。 (SPI: `jvppeteer`) |
| `QwenProxyChatClient` | 通义千问逆向代理对话客户端，Cookie 认证。 (SPI: `jvppeteer-qwen-proxy`) |
| `DoubaoProxyChatClient` | 豆包逆向代理对话客户端，Cookie 认证。 (SPI: `jvppeteer-doubao-proxy`) |

与 `utils-support-playwright-starter` 功能对等，SPI 名称加 `jvppeteer-` 前缀，
因此两个模块可以同时存在于类路径，不冲突。

---

## 浏览器定位

按以下顺序自动查找 Chromium 内核浏览器：

1. 显式传入 `executablePath`
2. 系统属性 `-Djvppeteer.executablePath=/path/to/chrome`
3. 环境变量 `JVPPETEER_EXECUTABLE_PATH` / `CHROME_PATH` / `CHROME_BIN` / `INCEPTION_CHROME`
4. 常见安装路径（Chrome / Edge / Chromium）
5. Playwright 浏览器缓存（`ms-playwright/chromium-*`）
6. jvppeteer 自身缓存（`~/.cache/jvppeteer`）

全部找不到时回退到 jvppeteer 自动下载（首次约 150MB，需要网络）。

---

## 反代对话客户端

```java
ChatClient client = ChatClient.create("jvppeteer-qwen-proxy",
        "token=xxx; ssxmod_itna=xxx");
String answer = client.model("qwen-plus").chatSync("你好");
```

```java
ChatClient client = ChatClient.create("jvppeteer-doubao-proxy",
        "sessionid=abc; ttwid=def; passport_csrf_token=ghi");
String answer = client.model("doubao-think").chatSync("你好");
```

Cookie 通过 `appKey` 传入。这两个客户端沿用 playwright-starter 的站点协议实现，
区别仅在浏览器驱动层（Jvppeteer 代替 Playwright）。

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用；仅浏览器路径可通过系统属性或环境变量覆盖。

---

## 依赖关系

```
utils-support-jvppeteer-starter
├── utils-support-common-starter
├── utils-support-crawler-starter
└── io.github.fanyong920:jvppeteer
```
