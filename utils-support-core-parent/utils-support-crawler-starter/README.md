# utils-support-crawler-starter

> utils 体系的**抓取 / 解析 SPI 层 + 爬虫数据模型**：可插拔的 Fetcher / Parser / Pipeline / Scheduler 等 SPI 接口、内置默认实现（JDK HttpClient / JSoup / 控制台输出 / FIFO 队列）、链式编程入口 `Spider`，以及 `SpiderRequest` / `SpiderResponse` / `SpiderResult` 等数据模型。完整的爬虫平台能力（REST 端点、定时任务平台）**不在**本模块，由 `spring-support-collect-starter` 承接。
>
> 版本：`4.0.0.51`｜JDK：`25`（字节码 25，由父 POM 统一，见 pom.xml 注释）｜包名：`com.chua.crawler.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心概念与一次爬取的数据流](#2-核心概念与一次爬取的数据流)
- [3. 核心 API 速查](#3-核心-api-速查)
- [4. 快速开始](#4-快速开始)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-crawler-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 抓取/解析的可插拔 SPI 契约 + 内置默认实现 + 爬虫数据模型，开箱即用 | 不是爬虫平台：pom 注释明确记载 `/spider/definitions`、`/spider/executions`、`/spider/proxy-pools` 三组控制器共 16 个端点已整体下线，能力由 `spring-support-collect-starter`（`/v2/collect/**`）承接 |
| 装配 | 纯 Java 链式构建（`Spider.create()`），`build()` 内置全部默认实现，不依赖容器即可跑通 | 不是 Spring 自动配置模块：本模块**没有** `AutoConfiguration.imports`、`spring.factories`，也不存在 `META-INF/extensions/` 注册文件（pom 注释明确「抓取层不走 Spring 容器，不需要任何自动配置」） |
| 抓取 | JDK `HttpClient`（GET/POST/PUT/DELETE），支持自定义头、Cookie、代理、超时、UA | 不含浏览器渲染：Playwright 实现在 `utils-support-playwright-starter`，另有 `utils-support-cdp-starter`、`utils-support-jvppeteer-starter` 注册同一组 SPI |
| 解析 | JSoup 解析 HTML（title / 纯文本 / meta），`a[href]` 链接提取与标准化 | 不含 JSON / XML / RSS / Sitemap 等专用解析器（接口文档提及，本模块未提供实现） |
| 扩展 | Fetcher / Parser / Pipeline 等均 SPI 化（`@Spi`），支持按名称替换与 `auto` 自动降级 | 去重不强依赖本模块：去重接口 `Deduplicator` 与默认实现 `MemoryDeduplicator` 位于 `utils-support-common-starter` |
| 调度 | FIFO 线程安全队列调度；`Spider.scheduler(Trigger)` 定时执行（委托 common 的 `JdkSchedulerProvider`） | 无声明式定时注解（如 `@Scheduler`），无 Redis 等分布式队列 |

### 1.3 规模

- Java 源文件 **29** 个（与 pom.xml 注释「29 个类」一致），顶层包 `com.chua.crawler.support`。
- `src/main/resources` 下无任何注册文件/配置文件，仅 `META-INF/native-image/com.chua/utils-support-crawler-starter/` 下的 `reflect-config.json`、`resource-config.json` 两份 GraalVM `native-image` 元数据。
- 模块名与包名均已从 `spider` 更名（`utils-support-spider-starter` → `utils-support-crawler-starter`，`com.chua.spider.support` → `com.chua.crawler.support`，见 pom.xml 注释）。

---

## 2. 核心概念与一次爬取的数据流

`Spider` 主入口接口把爬取拆成六个协作组件（`Spider.run()` 的 javadoc）：

| 组件 | 接口 | 职责 |
| --- | --- | --- |
| Scheduler 调度器 | `SpiderScheduler` | 出队待爬取 URL |
| Fetcher 抓取器 | `SpiderFetcher` | 去目标 URL 拿原始内容，不关心内容是什么 |
| Parser 解析器 | `SpiderParser` | 把原始内容拆成结构化数据（标题、正文、meta） |
| AiParser AI 增强 | `SpiderAiParser` | 智能总结、分类、结构化提取、翻译（可选） |
| Pipeline 回调 | `SpiderPipeline` | 消费采集到的数据（保存、打印、转发） |
| LinkExtractor 链接提取 | `SpiderLinkExtractor` | 提取新链接继续爬取 |

一次请求的完整链路（`DefaultSpider.processRequest` / `enqueueUrl`）：

1. **入队前过滤与去重**：URL 依次经过全部 `SpiderUrlFilter.accept()`，再经 `Deduplicator.isDuplicate()` / `markProcessed()` 去重后才进入 Scheduler 队列；
2. **出队**：`scheduler.dequeue()` 取出 `SpiderRequest`（队列空则结束）；
3. **深度/页数检查**：`maxDepth >= 0` 且 `depth > maxDepth` 时跳过；`maxPages > 0` 且结果数达到上限时停止；
4. **抓取**：`fetcher.fetch(request)`，`SpiderResponse.isSuccess()` = 状态码 2xx 且无 `error`，失败则按 `retryTimes` 重试（第 i 次重试前等待 `1000 * i` 毫秒）；
5. **解析**：`parser.parse(response)`，返回空表示该解析器无法处理；
6. **AI 增强**：若配置了 `aiParser`，调用 `summarize(result)`，非空结果写入 `result.getMetadata().put("aiSummary", summary)`；
7. **Pipeline 回调**：按添加顺序依次 `process(result)`（每个 Pipeline 有 `init()` / `destroy()` 生命周期回调）；
8. **结果登记**：`results.add(result)`（`getResults()` 返回不可修改列表）；
9. **链接续爬**：`linkExtractor.extract(response)`，每个链接以 `depth + 1` 入队继续上述流程；
10. **间隔控制**：若还有待处理请求，`Thread.sleep(site.getInterval())`。

默认装配（`DefaultSpiderBuilder.build()`，全部由本模块直接 `new`，不依赖 SPI 注册即可运行）：

| 组件 | 默认实现 |
| --- | --- |
| Fetcher | `new HttpFetcher(site != null ? site.getTimeout() : 30_000)` |
| Parser | SPI 默认扩展（`getNewDefaultExtension()`），取不到时回退 `new AutoParser()` |
| Scheduler | `new FifoScheduler()` |
| Deduplicator | `new MemoryDeduplicator()`（common 模块） |
| LinkExtractor | SPI 默认扩展，取不到时回退 `new HtmlLinkExtractor()` |
| Pipeline | 未配置任何 Pipeline 时追加 `new ConsolePipeline()` |

---

## 3. 核心 API 速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 最小抓取 | `Spider.create().addUrl(url).run()` | 阻塞执行完整生命周期，默认控制台输出 |
| 同步取结果 | `Spider.create().addUrl(url).build().runSync()` | 执行并返回 `List<SpiderResult>`（已运行中时返回空列表）；`runSync()` 是 `Spider` 实例方法（Builder 上没有，需先 `.build()`） |
| 并发抓取 | `.threads(int)` | 并发线程数，默认 1，`Math.max(1, n)` 兜底 |
| 结果回调 | `.pipeline(Consumer<SpiderResult>)` | Lambda 快速处理；也可 `.pipeline(SpiderPipeline)` 或 `.pipeline("console")` 按 SPI 名称 |
| 类型化输出（CSS 选择器） | `.as(Class<T>, Consumer<T>)` | 按 `@SpiderField(selector=...)` 映射 POJO；jsoup 不在类路径时打 warn 并跳过 |
| 类型化输出（AI 提取） | `.as(Class<T>, String aiProvider, String aiApiKey, Consumer<T>)` | 按 `@SpiderAi` / `@SpiderField(ai=...)` 用 ChatClient 提取 |
| AI 增强 | `.aiParser(String name, String apiKey)` | 按 SPI 名称加载 `SpiderAiParser`，默认链路只调用 `summarize()` 并写入 `metadata["aiSummary"]` |
| 定时执行 | `spider.scheduler(Trigger)`（`Spider` 实例方法，先 `.build()`） | 包装为 `Runnable` 委托 `JdkSchedulerProvider.schedule(this::runSync, trigger)`，返回 `ScheduledTask` 可 `cancel()`；首次触发 = Trigger 的下一次执行时刻（该时刻已过则延迟归 0 立即执行），之后同一实例反复 `runSync()`，`results` 不清理 |
| 按名称换组件 | `.fetcher("auto")`、`.parser("html")`、`.pipeline("console")` | 按 SPI 名称加载，找不到时打 warn 日志（不抛异常） |
| 精细请求 | `.addRequest(SpiderRequest)` | 自定义方法、头、Cookie、优先级、代理等；注意 Builder 只取 `request.getUrl()` 入种子队列 |

---

## 4. 快速开始

### 4.1 最小示例

```java
Spider.create()
    .addUrl("https://example.com")
    .run();
```

默认即可运行：JDK HttpClient 抓取、`AutoParser`/SPI 默认解析器解析、`ConsolePipeline` 控制台输出。

### 4.2 并发抓取 + 回调

```java
Spider.create()
    .addUrl("https://example.com")
    .threads(5)                     // 5 个线程并发
    .pipeline(result -> System.out.println(result.getTitle()))
    .run();
```

线程池为固定大小 `ThreadPoolExecutor(threads, threads, 0L, MILLISECONDS, new LinkedBlockingQueue<>(1024), Executors.defaultThreadFactory(), new CallerRunsPolicy())`；`run()` 内部用 `CountDownLatch(threads)` 等待全部工作线程结束。

### 4.3 站点配置

```java
SpiderSite site = SpiderSite.builder()
    .domain("example.com")
    .userAgent("Mozilla/5.0 ... Chrome/120.0.0.0 Safari/537.36")
    .interval(1000)                 // 请求间隔毫秒
    .retryTimes(3)                  // 失败重试次数
    .timeout(30000)                 // 单请求超时毫秒
    .maxDepth(2)                    // 只追 2 层链接
    .maxPages(100)                  // 最多抓 100 页
    .build();

List<SpiderResult> results = Spider.create()
    .site(site)
    .addUrl("https://example.com")
    .build()
    .runSync();
```

注意：`site.getUserAgent()` 与 `site.getTimeout()` 会在入队时透传到请求 `attributes`（键 `userAgent` / `timeoutMs`，若请求未单独指定），供 Fetcher 使用。

### 4.4 POJO 映射（CSS 选择器）

```java
public class Article {
    @SpiderField(selector = "h1.title")                 // attr 默认 "text"
    private String title;

    @SpiderField(selector = "meta[property=article:author]", attr = "content")
    private String author;

    @SpiderField(selector = "div.content", attr = "html")
    private String contentHtml;
    // getter / setter ...
}

Spider.create()
    .addUrl("https://example.com")
    .as(Article.class, article -> System.out.println(article.getTitle()))
    .run();
```

映射规则（`SpiderFieldMapper.map`）：`selector` 优先；`attr` 取值 `text` / `html` / 其他任意 HTML 属性名；提取为空时回落到 `defaultValue()`。支持字段类型：`String` / `int` / `long` / `double` / `boolean`（及其包装类型）。

### 4.5 POJO 映射（AI 提取）

```java
@SpiderAi("从文章页面中提取以下信息")          // 类级整体指令
public class Article {
    @SpiderField(ai = "文章标题")
    private String title;

    @SpiderField(ai = "发布时间，格式 yyyy-MM-dd")
    private String publishDate;
}

Spider.create()
    .addUrl("https://example.com/article")
    .as(Article.class, "deepseek", "sk-xxx", article -> System.out.println(article.getTitle()))
    .run();
```

AI 提取细节（源码事实）：文本截断到 8000 字符后调用 `ChatClient`（`ChatClient.create(ChatClientSetting.builder().provider(aiProvider).appKey(aiApiKey).build())`）；`SpiderAi.model()` 非空时作为模型名；AI 返回 JSON（可带 ``` 代码块包裹）优先用 Jackson 解析，失败回退手动解析。

### 4.6 定时执行

```java
// 每天凌晨 3 点执行：首次触发发生在下一个 3:00 时刻（非「立即」）
ScheduledTask task = Spider.create()
    .addUrl("https://news.example.com")
    .build()                // scheduler(Trigger) 是 Spider 实例方法，先 build()
    .scheduler(new CronTrigger("0 0 3 * * ?"));

// 取消调度
task.cancel();
```

---

## 5. 配置项

### 5.1 `SpiderSite` — 站点级配置（10 个字段，均为源码默认值）

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `domain` | String | 未设置（null） | 目标域名，如 `"example.com"` |
| `userAgent` | String | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36` | 请求 UA |
| `interval` | int | `1000` | 连续请求最小间隔（毫秒） |
| `retryTimes` | int | `3` | 失败最大重试次数 |
| `timeout` | int | `30000` | 单请求超时（毫秒） |
| `cookies` | String | 未设置 | 站点级 Cookie 字符串 |
| `headers` | Map<String,String> | 空 map | 站点级自定义请求头 |
| `respectRobotsTxt` | boolean | `false` | 是否遵守 `robots.txt`；**注意：字段已声明但 DefaultSpider 未消费（源码未提供实现）** |
| `maxDepth` | int | `-1` | 最大爬取深度；负数不限制（`0` 只爬种子） |
| `maxPages` | int | `0` | 最大抓取页数；`0` 或负数不限制 |

### 5.2 `SpiderRequest` 请求级属性（`attributes` 透传键）

| 键 | 消费方 | 说明 |
| --- | --- | --- |
| `timeoutMs` | `HttpFetcher` | 请求超时（毫秒），非正数回退 30000；入队时若无此键，自动写入 `site.getTimeout()` |
| `userAgent` | `HttpFetcher` | 请求 UA；入队时若无此键，自动写入 `site.getUserAgent()` |

### 5.3 其他模型字段

- `SpiderRequest`：`url`、`headers`、`cookies`、`cookieList`（`SpiderCookie`：`name`/`value`/`domain`/`path`/`secure`）、`method`（默认 `GET`，`HttpFetcher` 支持 `GET`/`POST`/`PUT`/`DELETE`）、`body`、`priority`（默认 `0`，FIFO 队列未按优先级排序）、`depth`、`referUrl`、`proxy`。
- `SpiderProxyConfig`：`proxyHost`、`proxyPort`、`proxyProtocol`（默认 `"HTTP"`；`HttpFetcher` 将 `SOCKS`/`SOCKS5` 映射为 SOCKS 代理，其余按 HTTP 代理）、`proxyUsername`、`proxyPassword`（**后两者 `HttpFetcher` 未消费，需认证时请自行确认实现**）。
- `SpiderResponse`：`request`、`statusCode`、`headers`、`rawContent`、`content`、`contentType`、`charset`、`contentLength`、`fetchTimeMs`、`error`；`isSuccess()` = 2xx 且无 `error`。
- `SpiderResult`：`url`、`depth`、`title`、`text`、`html`、`structured`、`links`、`aiSummary`、`aiCategory`、`metadata`、`contentType`、`extractedAt`。默认链路中 `metadata["aiSummary"]` 由 `aiParser.summarize()` 写入；`aiSummary`/`aiCategory` 字段与 `extract`/`classify`/`translate` 方法由具体 `SpiderAiParser` 实现决定是否填充，`DefaultSpider` 未触发。

### 5.4 配置文件

本模块**没有**任何 Spring 配置项（无 `application.yml` 键、无 `AutoConfiguration.imports`、无 `spring.factories`）。所有配置走 Java API（`SpiderSite`、`SpiderRequest`、Builder 链式方法）。

---

## 6. SPI 扩展点

### 6.1 本模块内置实现（`@Spi` 注解实际值，源码为准）

> 本模块接口 javadoc 中可见 `fetcher:http`、`parser:html` 等带前缀的拼写，但实现类注解与设计文档（`方案.md`）一致采用**无冒号分隔的简洁名称**：`"http"`、`"html"` 等。

| SPI 接口 | 方法 | 内置实现（`@Spi` 名） | 说明 |
| --- | --- | --- | --- |
| `SpiderFetcher` | `fetch(SpiderRequest)`、`supportedContentTypes()`（接口 default，返回空数组） | `HttpFetcher`（`"http"`，order=0）｜`AutoFetcher`（`"auto"`） | http：JDK HttpClient；auto：`collectNew()` 收集全部 Fetcher 实现（每个 SPI 名取首个，`collectNew()` 未按 order 排序），逐一尝试直到成功，全部失败返回最后一次失败响应 |
| `SpiderParser` | `parse(SpiderResponse)`、`supportedContentTypes()`（接口抽象方法） | `HtmlParser`（`"html"`，`@ConditionalOnClass("org.jsoup.Jsoup")`）｜`AutoParser`（`"auto"`） | html：title/纯文本/meta；auto：逐一尝试，全部失败返回仅含 URL 的裸结果；`supportedContentTypes()` 返回 `{"*/*"}`（由内部 Parser 决定） |
| `SpiderPipeline` | `process(SpiderResult)`、`init()`、`destroy()` | `ConsolePipeline`（`"console"`） | 控制台格式化输出（URL/标题/AI 总结/结构化字段/内容预览） |
| `SpiderScheduler` | `enqueue` / `dequeue` / `hasNext` / `size` / `clear` | `FifoScheduler`（`"fifo"`） | `ConcurrentLinkedQueue` + `AtomicInteger`，线程安全 FIFO |
| `SpiderLinkExtractor` | `extract(SpiderResponse)`、`supportedContentTypes()`（接口 default，返回空数组） | `HtmlLinkExtractor`（`"html"`，`@ConditionalOnClass("org.jsoup.Jsoup")`） | `a[href]` 提取，相对转绝对，过滤非 `http(s)` 与 `javascript:`/`mailto:`/`tel:`/`sms:`/`file:`/`data:`/`blob:`、纯锚点，去尾部斜杠与 fragment |
| `SpiderUrlFilter` | `accept(SpiderRequest)` | `DomainUrlFilter`（`"domain"`） | 域名白名单（精确或子域名）；默认构造器不限制；需通过 `.urlFilter(...)` 添加，默认不过滤 |
| `SpiderAiParser` | `summarize` / `extract` / `classify` / `translate` | **本模块无实现**（接口 javadoc 说明实现类通过 `@Spi("ai")` 注册，模块内未见实现类） | 基于 common 的 `ChatClient` |
| `Spider` | — | `DefaultSpider`（`"default"`，order=0，`@SpiDefault`） | 默认主入口 |

`AutoParser` 内部遍历 `ServiceProvider.of(SpiderParser.class).getDefinitions(null)`、显式排除自身后实例化（避免递归）；`AutoFetcher` 用 `collectNew()` 并过滤掉自身。两者全部失败时：AutoFetcher 返回最后一次失败响应，AutoParser 返回仅含 URL 的裸结果（text 截断 200 字符）。

### 6.2 注册机制与第三方实现

- SPI 加载由 `utils-support-common-starter` 的 `ServiceProvider` 完成，配置目录为 `META-INF/extensions/`，文件名即接口全限定名（如 `com.chua.crawler.support.SpiderFetcher`），行格式：`实现类全限定名` 或 `别名=实现类全限定名`。
- **本模块自身未提供任何 `META-INF/extensions/` 注册文件**（pom 注释声称已迁移，但当前 `src/main/resources` 与 `target/classes` 中均不存在）；因此按名称查找（如 `.fetcher("http")`、`.parser("html")`、`.pipeline("console")`）是否命中，取决于运行时 classpath 上的注册文件与 common 的扫描行为，取不到时 Builder 仅打 warn 日志。
- 第三方浏览器渲染实现注册在同一 SPI 上（同仓已见，均位于 `utils-support-network-parent` 下）：`utils-support-playwright-starter`、`utils-support-cdp-starter`、`utils-support-jvppeteer-starter` 均注册了 `SpiderFetcher` / `SpiderParser`；其中 `utils-support-jvppeteer-starter` 额外注册了 common 的 `ChatClient` SPI（`utils-support-playwright-starter` 的 `META-INF/extensions/` 下也有 `ChatClient` 注册文件，但 `utils-support-cdp-starter` 没有）。注意 `utils-support-extra-parent` 下另有一个同名 `utils-support-playwright-starter`，其注册文件仅有 `com.chua.playwright.support.engine.Engine`，未注册 crawler SPI。
- 去重：使用 common 的 `Deduplicator` 接口（默认 `MemoryDeduplicator`），`SpiderDeduplicator` 仅为占位说明类（防实例化的工具类），不参与实现。

### 6.3 自定义扩展点示例（按源码 API 编写）

```java
// 1. 实现 SPI 接口
@Spi("my-fetcher")                      // 注册 SPI 名称（配合 META-INF/extensions 注册文件）
public class MyFetcher implements SpiderFetcher {
    @Override
    public SpiderResponse fetch(SpiderRequest request) {
        // ... 自定义抓取逻辑
        return SpiderResponse.builder().request(request).statusCode(200).build();
    }
}

// 2. 用法一：直接传入实例
Spider.create()
    .fetcher(new MyFetcher())
    .addUrl("https://example.com")
    .run();

// 3. 用法二：按 SPI 名称加载（需要类路径上有对应注册）
Spider.create()
    .fetcher("my-fetcher")
    .addUrl("https://example.com")
    .run();
```

### 6.4 流程编排节点

`flow.SpiderFlowNode` 实现 common 的 `SpiderNode` 接口，把一次爬取封装为流程节点（属性：`urls` 必填（缺失抛 `IllegalArgumentException`）、`threads` 默认 1、`maxDepth` 默认 -1、`maxPages` 默认 0、`interval` 默认 1000、`retryTimes` 默认 3）。执行 `builder.build().runSync()`（先 `Spider.create()` 返回 Builder 配置，`build()` 得实例后调用实例方法 `runSync()`），结果写入上下文：`context.setAttribute("spider.result", results)` 且 `context.setData(results)`。

---

## 7. 构建与依赖

### 7.1 构建

- 父 POM：`com.chua:utils-support-core-parent:4.0.0.51`（`relativePath=../pom.xml`）；版本/依赖管理统一由 `utils-support-parent-starter` 提供。
- 构建命令（需父 POM 已安装到本地仓库）：

```bash
mvn -f utils-support-core-parent/utils-support-crawler-starter/pom.xml clean install
# 或从 utils-support-parent-starter 根目录按坐标构建
mvn -pl utils-support-core-parent/utils-support-crawler-starter -am clean install
```

- 本模块 properties 仅声明 `maven.compiler.parameters=true` 与 `project.build.sourceEncoding=UTF-8`（UTF-8 无 BOM 构建）；编译插件显式配置 `encoding=UTF-8`。

### 7.2 依赖一览（pom.xml 依据）

| 依赖 | 坐标 | scope | 用途（源码事实） |
| --- | --- | --- | --- |
| 核心契约 | `com.chua:utils-support-common-starter`（版本 ${project.version}） | compile | SPI 机制、`Deduplicator`/`MemoryDeduplicator`、`ChatClient`、`JdkSchedulerProvider`、`Trigger`、`ScheduledTask`、`FlowContext` 等 |
| HTML 解析 | `org.jsoup:jsoup`（`${jsoup.version}`） | compile | `Jsoup.parse` 供 HtmlParser / SpiderFieldMapper / HtmlLinkExtractor 运行期调用 |
| HTTP 备选 | `com.squareup.okhttp3:okhttp`（`${okhttp.version}`） | provided | 未在代码中直接 import（核心实现用 JDK HttpClient） |
| JSON | `com.fasterxml.jackson.core:jackson-databind` | compile | `SpiderFieldMapper` 中 `new ObjectMapper()` 解析 AI 返回 |
| Spring | `spring-boot-autoconfigure` / `spring-web` / `spring-context` / `spring-boot-starter-web` | compile | 运行期反射 `@AutoConfiguration` / `@RestController` / `@Configuration`（依赖传递需要，非自动装配） |
| 注解 | `jakarta.annotation:jakarta.annotation-api` | compile | 运行期反射 `@PostConstruct` / `@PreDestroy` |

具体版本号由父 POM 属性（`jsoup.version`、`okhttp.version` 等）管理；`dtrees.txt` 历史依赖树记录（4.0.0.42 时）为 jsoup 1.17.2、okhttp 4.12.0，当前以父 POM 为准（源码未在模块内提供最终版本值）。

---

## 8. 边界：这些能力不在本模块

| 能力 | 位置 / 说明 |
| --- | --- |
| 爬虫平台 REST 端点（`/spider/definitions`、`/spider/executions`、`/spider/proxy-pools` 等 16 个） | 已下线，由 `spring-support-collect-starter`（`/v2/collect/**`）承接（pom 注释） |
| 定时调度平台（SpiderRunner / SpiderTimerService） | 同上，已下线；本模块仅提供 `Spider.scheduler(Trigger)` 委托 `JdkSchedulerProvider` |
| Spring 自动配置 / `AutoConfiguration.imports` / `spring.factories` | 本模块不存在，也无任何配置文件 |
| 浏览器渲染抓取（Playwright 等） | `utils-support-playwright-starter`；另有 `utils-support-cdp-starter`、`utils-support-jvppeteer-starter` 注册同一 SPI |
| 分布式（Redis）队列 / 去重 | 未实现；`SpiderScheduler` 仅有 FIFO 默认实现，`Deduplicator` 默认内存实现，接口在 common 可自行替换 |
| 声明式定时注解（`@Scheduler` 等） | 本模块源码不存在此类注解（旧 README 中的示例与当前源码不符，已按源码更正） |
| JSON / XML / RSS / Sitemap 专用解析器、优先级/延迟/分布式调度器 | 接口文档提及的扩展方向，本模块未提供实现（源码未提供） |
| `respectRobotsTxt` 行为 | 字段存在且有 javadoc，但 `DefaultSpider` 未消费（源码未提供实现） |
| 代理用户名/密码认证 | `SpiderProxyConfig` 字段存在，`HttpFetcher` 仅使用 host/port/protocol（源码未提供认证实现） |
| 按名称 SPI 加载的保证 | `.fetcher("http")` / `.parser("html")` 等按名称查找依赖运行时注册与 common 扫描；默认装配不依赖注册文件（全部直接 `new`） |

---

## 附：本文档的事实口径

- 全部类名、方法名、字段名、SPI 名称、默认值均逐字摘自本模块源码（`Spider` / `DefaultSpider` / 全部 SPI 接口 / 内置实现 / 数据模型 / `@Spi` 注解值）与 `pom.xml`（版本、依赖、模块历史注释）。
- 标注「源码未提供」处为接口文档提及或字段存在但**没有对应实现**的事实（如 AI 字段填充、robots.txt 消费、代理认证），未做任何补充性杜撰。
- 外部事实（第三方模块注册文件、common 的 SPI 解析目录）取自同仓实际文件核查。