# utils-support-playwright-starter

> 基于 Microsoft Playwright 的浏览器自动化模块：URL 截图 / 长截图 / 页面加载等待；爬虫浏览器渲染抓取与解析；通用浏览器引擎 SPI；通义千问 / 豆包 Cookie 逆向对话客户端；真实浏览器业务流程压测。
>
> 版本：`4.0.0.51`（继承父模块 `utils-support-network-parent`）｜JDK：`25`（仓库默认 `java.version=25` / `maven.compiler.release=25`）｜包名：`com.chua.playwright.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
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
    <artifactId>utils-support-playwright-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 截图 | 基于 Playwright Chromium 的 URL 转图片、整页长截图、页面加载等待 | 不是通用图像处理库 |
| 爬虫 | 支持 JavaScript 渲染的抓取器（`SpiderFetcher`）与解析器（`SpiderParser`） | 不是 HTTP 静态抓取器（请用 common 的 `HttpClientFactory`） |
| AI | 通过浏览器页面内原生 `fetch` 复用站点签名的 Cookie 逆向对话客户端（通义千问 / 豆包） | 不是官方 OpenAI 兼容 API 客户端，不管理 API Key 计费 |
| 压测 | 用真实浏览器执行业务流程（打开/填写/点击/等待/断言/截图）并产出 HTML 报告 | 不是协议级接口压测工具 |
| 配置 | 零配置模块，全部参数通过 API 传入 | 无 Spring Boot 自动配置、无 properties/yml 配置项 |

**依赖口径**：本模块属于最底层工具库（`utils-*`），只依赖 `utils-support-common-starter`（编译期）与 `utils-support-crawler-starter`（provided，仅 SPI 契约），不依赖任何上层业务 starter。

### 1.3 源码地图

| 子包 | 类 | 说明 |
| --- | --- | --- |
| `screenshot` | `PlaywrightScreenshot`（含 `ScreenshotConfig`） | URL 截图、长截图、加载等待、浏览器手动安装指引 |
| `spider` | `PlaywrightFetcher` | 爬虫浏览器渲染抓取器（SPI `playwright`） |
| `spider` | `PlaywrightParser` | 爬虫浏览器渲染解析器（SPI `playwright`） |
| `browser` | `PlaywrightBrowserEngine`（含 `PlaywrightPageAdapter`） | 通用 `BrowserEngine` SPI 的 Playwright 实现 |
| `qwen` | `QwenBrowserSession` / `QwenProxyChatClient` | 通义千问浏览器会话与逆向对话客户端（SPI `qwen-proxy`） |
| `doubao` | `DoubaoBrowserSession` / `DoubaoProxyChatClient` | 豆包浏览器会话与逆向对话客户端（SPI `doubao-proxy`） |
| `benchmark` | `PlaywrightBusinessBenchmark`（含 `BusinessStep` / `StepResult` / `BusinessRun` / `BenchmarkSummary` / `Builder`） | 真实浏览器业务压测框架 |

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| URL 截图 / 长截图 | `PlaywrightScreenshot.screenshot(url, outFile)` / `fullPageScreenshot(url, outFile)` | 均有带 `Consumer<ScreenshotConfig>` 的重载 |
| 页面加载等待 | `ScreenshotConfig.setWaitSelector(...)` / `setExtraWaitMillis(...)` | 等待指定元素或网络空闲后再截图 |
| 浏览器渲染抓取 | `new PlaywrightFetcher().fetch(SpiderRequest)` | SPI 名 `playwright`，返回渲染后 HTML；用完调 `close()` |
| 浏览器渲染解析 | `new PlaywrightParser().parse(SpiderResponse)` | SPI 名 `playwright`，提取 JS 执行后的标题与纯文本 |
| 通用浏览器引擎 | `BrowserEngine.of("playwright")`（SPI）→ `PlaywrightBrowserEngine` | `openPage(BrowserOptions)` 懒启动 Chromium，返回 `BrowserPage` |
| 通义千问对话 | `ChatClient.create("qwen-proxy", cookieString)` | appKey 传 Cookie 串 `token=xxx; ssxmod_itna=xxx` |
| 豆包对话 | `ChatClient.create("doubao-proxy", cookieString)` | appKey 传 Cookie 串 `sessionid=xxx; ttwid=xxx; passport_csrf_token=xxx` |
| 业务流程压测 | `PlaywrightBusinessBenchmark.builder()...build().run()` | 输出含截图、测试点、吞吐量与延迟分位的 HTML 报告 |
| 浏览器安装失败排查 | `PlaywrightScreenshot.printManualInstallGuide()` | 打印 Playwright 1.49.0 Chromium 的三种手动安装方式 |

> ChatClient 的三个静态工厂 `create(ChatClientSetting)` / `create(String provider, String apiKey)` / `create(String provider, String apiKey, String baseUrl)` 定义在 `utils-support-common-starter` 的 `com.chua.common.support.ai.chat.ChatClient` 中，本模块通过 SPI 注册 `qwen-proxy` 与 `doubao-proxy` 两个实现。

---

## 3. 快速开始

### 3.1 URL 截图与长截图

```java
import com.chua.playwright.support.screenshot.PlaywrightScreenshot;
import com.chua.playwright.support.screenshot.PlaywrightScreenshot.ScreenshotConfig;

// 视口截图（默认 1280x720，等待网络空闲）
PlaywrightScreenshot.screenshot("https://example.com", "out/page.png");

// 整页长截图
PlaywrightScreenshot.fullPageScreenshot("https://example.com", "out/full.png");

// 自定义：视口、等待元素、额外等待、自定义浏览器路径
PlaywrightScreenshot.screenshot("https://example.com", "out/custom.png", config -> {
    config.setViewportWidth(1920)
          .setViewportHeight(1080)
          .setWaitSelector("#main")          // 等待元素出现（否则等 NETWORKIDLE）
          .setWaitTimeout(30_000)
          .setExtraWaitMillis(2_000)         // 动态内容额外渲染时间
          .setExecutablePath("/path/to/chrome"); // 复用本机 Chrome/Chromium
});
```

浏览器二进制缺失导致失败时，会自动打印 `printManualInstallGuide()` 的安装指引（npm 安装 / 离线复制 / 指定已有浏览器路径）。

### 3.2 爬虫浏览器渲染抓取与解析

`SpiderRequest` / `SpiderResponse` / `SpiderResult` 来自 `utils-support-crawler-starter`：

```java
import com.chua.crawler.support.model.SpiderRequest;
import com.chua.playwright.support.spider.PlaywrightFetcher;
import com.chua.playwright.support.spider.PlaywrightParser;

try (PlaywrightFetcher fetcher = new PlaywrightFetcher()) {
    // 导航 + 等待 NETWORKIDLE + 额外 1s 动态渲染，返回渲染后的完整 HTML
    var response = fetcher.fetch(SpiderRequest.builder()
            .url("https://example.com/spa-page")
            .build());

    try (PlaywrightParser parser = new PlaywrightParser()) {
        // 在浏览器中重新渲染 HTML，提取 JS 执行后的标题与 body.innerText
        var result = parser.parse(response);
        System.out.println(result.getTitle());
        System.out.println(result.getText());
    }
}
```

### 3.3 通用浏览器引擎 SPI

```java
import com.chua.common.support.browser.BrowserEngine;
import com.chua.common.support.browser.BrowserOptions;
import com.chua.common.support.browser.BrowserPage;

try (BrowserEngine engine = BrowserEngine.of("playwright")) {
    BrowserPage page = engine.openPage(BrowserOptions.builder()
            .headless(true)
            .build());
    page.navigate("https://example.com");
    byte[] png = page.screenshot(true);   // 整页 PNG
}
```

> `BrowserEngine.of(name)` 定义在 common 的 `BrowserEngine` 接口中；`PlaywrightBrowserEngine` 在首次 `openPage` 时按需启动 Chromium，支持 `headless`、`args`、`executablePath`。注意：`fetchStream` 在该实现中抛出 `UnsupportedOperationException`；`setCookies` / `cookies()` 为空实现（源码注释说明本次 SPI 只用于解析/注册，未做细粒度 cookie 实现）。

### 3.4 通义千问 / 豆包 Cookie 逆向对话

```java
import com.chua.common.support.ai.chat.ChatClient;

// 通义千问：appKey = 网页版 Cookie 串，页面内原生 fetch 自动携带 ssxmod_itna 指纹
try (ChatClient qwen = ChatClient.create("qwen-proxy", "token=xxx; ssxmod_itna=xxx")) {
    String answer = qwen.model("qwen-plus").chatSync("你好");
}

// 豆包：appKey = 网页版 Cookie 串，页面内原生 fetch 由字节前端 hook 注入 a_bogus / msToken 签名
try (ChatClient doubao = ChatClient.create("doubao-proxy",
        "sessionid=abc; ttwid=def; passport_csrf_token=ghi")) {
    String answer = doubao.model("doubao-think").thinking(true).chatSync("你好");
}
```

两个客户端均实现 common 的 `ChatClient`：支持链式参数（`model` / `temperature` / `maxTokens` / `system` / `thinking` / `smartSearch` / `topP` / `stop` / `seed` / `responseFormat` / `extraBody` / `skill` 等）、流式回调 `chat(prompt, consumer)`、图片 / 附件 / 工具注入、`generateImage` / `generateVideo`、`session(...)` 会话保持与 `newChat()` 重置。豆包客户端 `close()` 时会顺带删除豆包侧会话。

### 3.5 真实浏览器业务压测

```java
import com.chua.playwright.support.benchmark.PlaywrightBusinessBenchmark;
import com.chua.playwright.support.benchmark.PlaywrightBusinessBenchmark.BusinessStep;

PlaywrightBusinessBenchmark benchmark = PlaywrightBusinessBenchmark.builder()
        .baseUrl("https://example.com")
        .step(BusinessStep.open("打开首页", "/login"))
        .step(BusinessStep.fill("输入用户名", "#username", "admin"))
        .step(BusinessStep.fill("输入密码", "#password", "123456"))
        .step(BusinessStep.click("点击登录", "#login-btn"))
        .step(BusinessStep.waitFor("等待首页加载", "#dashboard", 10000))
        .step(BusinessStep.assertText("断言欢迎语", "#welcome", "欢迎"))
        .step(BusinessStep.screenshot("登录成功截图"))
        .concurrency(4)          // 并发用户数，默认 4
        .iterations(10)          // 每用户迭代次数，默认 10
        .reportPath("target/playwright-business.html")
        .build();

benchmark.run();   // 返回 BenchmarkSummary，并写出 HTML 报告
```

指标口径与接口压测一致：总执行数、错误率、成功率、RPS（业务/秒）、平均耗时、标准差、p50 / p95 / p99 / p99.9、最大耗时；报告内含测试参数、测试点清单、最近一次运行的步骤明细与 Base64 内嵌业务截图。

---

## 4. 配置项

本模块为**零配置模块**：不引入 Spring Boot 自动配置，`src/main/resources` 下没有任何 properties/yml 配置文件，也没有可用的外部配置键。所有行为参数均通过 Java API 传入。

### 4.1 PlaywrightScreenshot.ScreenshotConfig（截图参数）

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `viewportWidth` / `viewportHeight` | `1280` / `720` | 视口大小 |
| `fullPage` | `false` | 是否整页长截图 |
| `waitSelector` | 无 | 等待指定元素出现；未设置时等待 `NETWORKIDLE` |
| `waitTimeout` | `30000` ms | 等待元素超时 |
| `extraWaitMillis` | `0` | 动态内容额外等待毫秒数 |
| `locale` | `zh-CN` | 浏览器区域 |
| `timezoneId` | `Asia/Shanghai` | 浏览器时区 |
| `extraHttpHeaders` | 无 | 额外 HTTP 头（`Map<String, String>`） |
| `slowMo` | `0` | 放慢操作毫秒数 |
| `executablePath` | 无 | 指定已有 Chrome/Chromium 可执行文件路径 |
| `baseUrl` | 由 `screenshot(url, ...)` 传入 | 页面地址 |

超时口径：默认操作超时 30 秒，页面导航超时 60 秒（源码常量 `DEFAULT_TIMEOUT` / `DEFAULT_NAVIGATION_TIMEOUT`）。

### 4.2 PlaywrightBusinessBenchmark.Builder（压测参数）

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `baseUrl` | `http://127.0.0.1:8080` | 业务地址，`open` 的相对路径会拼接该地址 |
| `steps` | 必填（空则 `build()` 抛 `IllegalArgumentException`） | 业务测试点列表 |
| `concurrency` | `4` | 并发用户数（每用户独立 BrowserContext） |
| `iterations` | `10` | 每用户迭代次数 |
| `reportPath` | `target/playwright-business.html` | HTML 报告输出路径 |
| `headless` | `true` | 是否无头模式 |
| `executablePath` | 无 | 指定浏览器可执行文件路径 |
| `waitAfterStepMs` | `0` | 每个步骤后的等待毫秒数 |

### 4.3 逆向对话客户端参数

- 认证：`ChatClientSetting.appKey` 传网页版 Cookie 串（分号分隔的 `k=v`），千问注入 `.qwen.ai` 域，豆包注入 `.doubao.com` 与 `.bytedance.com` 域。
- 基础地址：千问固定 `https://chat.qwen.ai`（源码常量 `DEFAULT_BASE_URL`）；豆包默认 `https://www.doubao.com`，可通过 `ChatClientSetting.baseUrl` 覆盖。
- 模型：未指定时千问回退 `qwen3.8-max`；豆包回退 `ChatClientSetting.model`。
- 深度思考 / 联网搜索：`thinking(boolean)` / `smartSearch(boolean)`；豆包的 `use_deep_think` / `use_auto_cot` 等也可经 `extraBody(Map)` 透传。
- 浏览器侧固定参数（源码常量，不可配）：千问页面加载 60s、响应等待 120s；豆包启动/导航 60s、默认用户数据目录 `./.doubao_browser_data`；两者均带反检测启动参数（`--disable-blink-features=AutomationControlled` 等）与 Chrome/130 UA。

---

## 5. SPI 扩展点

本模块通过 `META-INF/extensions` 注册以下 SPI 实现（均带 `@ConditionalOnClass("com.microsoft.playwright.Playwright")` 条件，classpath 缺少 Playwright 时自动跳过）：

| SPI 接口（来自） | 注册名 | 实现类 |
| --- | --- | --- |
| `com.chua.crawler.support.SpiderFetcher`（crawler-starter） | `playwright` | `PlaywrightFetcher` |
| `com.chua.crawler.support.SpiderParser`（crawler-starter） | `playwright` | `PlaywrightParser` |
| `com.chua.common.support.ai.chat.ChatClient`（common-starter） | `doubao-proxy` | `DoubaoProxyChatClient` |
| `com.chua.common.support.ai.chat.ChatClient`（common-starter） | `qwen-proxy` | `QwenProxyChatClient` |

`PlaywrightBrowserEngine` 标注 `@Spi("playwright")` 实现了 common 的 `BrowserEngine` 契约，但本模块的 `META-INF/extensions` 下**没有** `BrowserEngine` 的注册文件（common 中目前只注册了 `cdp` 实现），因此按名称装配前请先确认运行环境中的注册情况。

GraalVM native-image 配置（`META-INF/native-image/com.chua/utils-support-playwright-starter/`）：

- `reflect-config.json`：注册 `DoubaoProxyChatClient` 与 `QwenProxyChatClient` 的全部构造器/方法/字段反射；
- `resource-config.json`：包含 `META-INF/extensions/.*`。

---

## 6. 构建与依赖

```text
utils-support-playwright-starter
├── com.microsoft.playwright:playwright  1.63.0   （编译依赖，浏览器二进制由 Playwright 机制下载）
├── utils-support-common-starter                   （同版本，编译依赖：SPI 契约、工具、JSON、浏览器/AI 契约）
└── utils-support-crawler-starter                  （provided：SpiderFetcher/SpiderParser 契约与请求/响应模型）
```

- 父模块：`utils-support-network-parent`（4.0.0.51），聚合于仓库根 `utils-support-parent-starter`。
- 编译：`mvn -o -q compile -DskipTests`（JDK 25）。
- 运行前需保证本机存在 Playwright 对应的 Chromium 浏览器；安装失败时参考 `PlaywrightScreenshot.printManualInstallGuide()` 输出的指引（Playwright 版本 1.49.0，浏览器目录：Windows `%USERPROFILE%\AppData\Local\ms-playwright`、macOS `~/Library/Caches/ms-playwright`、Linux `~/.cache/ms-playwright`）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `SpiderFetcher` / `SpiderParser` 接口及 `SpiderRequest` / `SpiderResponse` / `SpiderResult` 模型 | `utils-support-crawler-starter` |
| `BrowserEngine` / `BrowserPage` / `BrowserOptions` 契约与 `cdp` 引擎实现 | `utils-support-common-starter`（`browser` 包） |
| `ChatClient` 契约、`ChatClientSetting`、`ChatResponse`、`AiUsage`、`Attachment`、`ChatTool` | `utils-support-common-starter`（`ai.chat` 包） |
| `QwenChatResult` / `DoubaoChatResult` / `DoubaoConstants` | `utils-support-common-starter`（`browser.chat` 包） |
| HTTP 静态抓取、代理、下载 | `utils-support-common-starter`（`network` 包）及对应 HTTP starter |
| Spring Boot 自动配置、配置文件属性 | 无（本模块零配置，参数仅经 API 传入） |
| 浏览器二进制分发 | 由 Playwright 依赖的下载机制负责，本模块不打包浏览器 |
