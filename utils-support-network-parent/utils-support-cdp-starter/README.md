# utils-support-cdp-starter

轻量 CDP 浏览器抓取模块。复用 `utils-support-common-starter` 内置的 JDK CDP 引擎，
**不依赖 Playwright / Jvppeteer / Selenium**，只使用 JDK 的 `ProcessBuilder` 与
`java.net.http.WebSocket`，与 `playwright-starter`、`jvppeteer-starter` 的 SPI 对等。

## 模块分工

| 位置 | 内容 |
| --- | --- |
| `utils-support-common-starter` | 引擎本体 `com.chua.common.support.browser.*`：`BrowserEngine`（SPI 名 `cdp`）、`BrowserPage`、`BrowserOptions`、`CdpScreenshot` |
| `utils-support-cdp-starter` | 爬虫适配器：`SpiderFetcher` / `SpiderParser` 的 `cdp` 实现 |
| `utils-support-openai-starter` | Inception 通道直接复用该引擎，不再自带一份 CDP 代码 |

> 适配器没有放进 `common-starter`，因为 `SpiderFetcher` / `SpiderParser` 在
> `crawler-starter` 里，而 `crawler-starter` 依赖 `common-starter`，反向放会形成
> 依赖环。适配器也没放进 `crawler-starter`，因为 `AutoParser` 会收集所有已注册的
> parser，直接注册会让所有爬虫默认拉起浏览器。独立模块 = 用则加、不用则零影响。

## 依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-cdp-starter</artifactId>
    <version>4.0.0.42</version>
</dependency>
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-crawler-starter</artifactId>
    <version>4.0.0.42</version>
</dependency>
```

`crawler-starter` 在该模块里是 `provided`，需要业务方自己引入。

## 用法一：爬虫 SPI

```java
Spider spider = DefaultSpider.builder()
        .fetcher(ServiceProvider.of(SpiderFetcher.class).getNewExtension("cdp"))
        .parser(ServiceProvider.of(SpiderParser.class).getNewExtension("cdp"))
        .build();
```

也可以只替换其中一环：`fetcher("auto")` / `parser("auto")` 会把 `cdp` 作为降级实现之一。

## 用法二：浏览器引擎 SPI

```java
try (BrowserEngine engine = BrowserEngine.cdp();
     BrowserPage page = engine.openPage(new BrowserOptions().setHeadless(true))) {
    page.navigate("https://example.com");
    System.out.println(page.title());

    // 在页面上下文里发请求，分片回传（可用于 SSE）
    page.fetchStream("GET", "https://example.com/", null, Map.of("accept", "text/html"),
            30_000L, new BrowserPage.FetchSink() {
                @Override
                public void onStatus(int status) {
                    System.out.println("status=" + status);
                }

                @Override
                public void onChunk(String chunk) {
                    System.out.print(chunk);
                }
            });

    byte[] png = page.screenshot(true);
    Files.write(Path.of("D:/tmp/example.png"), png);
}
```

`BrowserEngine.of(name)` 每次返回新实例，由调用方 `close()`；浏览器进程在首次
`openPage` 时才启动。

## 用法三：截图工具

API 与 `PlaywrightScreenshot` / `JvppeteerScreenshot` 保持一致：

```java
CdpScreenshot.screenshot("https://example.com", "D:/tmp/a.png");
CdpScreenshot.fullPageScreenshot("https://example.com", "D:/tmp/full.png",
        config -> config.setWaitSelector("#main").setExtraWaitMillis(1000));
```

## 浏览器探测顺序

1. 显式传入的 `executablePath`
2. 系统属性 `chua.browser.chrome`（兼容 `inception.chrome`）
3. 环境变量 `CHUA_CHROME` / `INCEPTION_CHROME` / `CHROME_PATH` / `CHROME_BIN`
4. 常见安装路径（Chrome / Chromium / Edge，Windows / macOS / Linux）
5. Playwright 缓存中的 Chromium（`ms-playwright/chromium-*`）

找不到时抛出明确异常并给出配置方式。

## 与另外两个模块的差别

| 能力 | cdp-starter | playwright-starter | jvppeteer-starter |
| --- | --- | --- | --- |
| 第三方依赖 | 无（仅 JDK） | Playwright + 自带 Chromium | Jvppeteer + 自带 Chromium |
| 页面渲染 / 截图 | 支持 | 支持 | 支持 |
| 页面内流式请求 | 支持 | 支持 | 支持 |
| 元素级交互（点击、键盘、等待响应） | 不支持（按需可扩展） | 支持 | 支持 |
| 反代对话客户端（Qwen / Doubao） | 不提供 | 提供 | 提供 |

元素级交互能力弱是该实现的取舍：它面向「渲染 + 截图 + 页面内请求」的轻量场景，
不试图替代 Playwright。

## 验证记录

- `BrowserEngine.of("cdp")` → `CdpBrowserEngine`
- `example.com`：标题、HTML、整页截图、页面内 GET 流式读取均通过
- `SpiderFetcher` / `SpiderParser` 的 `cdp` SPI 发现与解析通过
- Inception 真实上游：同步与流式均返回正常
