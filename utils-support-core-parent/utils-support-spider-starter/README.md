# utils-support-spider-starter — 爬虫模块

基于 SPI 的可插拔爬虫框架，支持多线程并发、自动降级、AI 智能解析。

---

## 架构图

```
┌──────────────────────────────────────────────────────────────────────┐
│                      Spider.create().run()                          │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                   DefaultSpider (引擎)                        │   │
│  │  @SpiDefault                                                   │   │
│  │  内置：HttpFetcher + HtmlParser + ConsolePipeline              │   │
│  │        + FifoScheduler + MemoryDeduplicator                   │   │
│  │  特性：多线程并发 │ 自动重试 │ 自动降级                        │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                  │                                   │
│  ┌───────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ │
│  │ Fetcher   │ │ Parser   │ │Pipeline  │ │Scheduler │ │Dedup.    │ │
│  │ 抓取器    │ │ 解析器   │ │ 回调      │ │ 调度器   │ │ 去重器   │ │
│  ├───────────┤ ├──────────┤ ├──────────┤ ├──────────┤ ├──────────┤ │
│  │ "http"   │ │ "html"   │ │"console" │ │ "fifo"   │ │ common   │ │
│  │ "playwr."│ │"playwr." │ │ consumer  │ │          │ │ MEM/REDIS│ │
│  │ "auto"   │ │ "auto"   │ │ 自定义    │ │ 自定义   │ │          │ │
│  └───────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘ │
│                              │                                       │
│                    ┌─────────▼────────┐                             │
│                    │   SpiderAiParser  │                             │
│                    │   @Spi("ai")      │                             │
│                    │   ChatClient 驱动  │                             │
│                    └──────────────────┘                             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 执行模型

Spider 是<b>一次性执行</b>的爬虫引擎：调用 `run()` 会阻塞直至队列中的 URL 全部处理完成并返回。

内置的 `SpiderScheduler` 是 <b>URL 队列调度器</b>（FIFO / 优先级 / 延迟队列），<b>不负责周期触发</b>。

如果需要定时 / 周期执行爬虫任务，请使用外部的 `task/scheduler` 模块，将 `spider.run()` 包装为 `Runnable` 注册即可。

### 声明式定时（@Scheduler 注解）

```java
import com.chua.common.support.task.scheduler.Scheduler;

@Component
public class MyCrawler {

    // 每天凌晨 3 点执行一次
    @Scheduler(cron = "0 0 3 * * ?")
    public void crawlDaily() {
        Spider.create()
                .addUrl("https://news.example.com")
                .pipeline(result -> saveToDb(result))
                .run();
    }

    // 每 5 分钟执行一次
    @Scheduler(initialDelay = 1, fixedRate = 5, timeUnit = TimeUnit.MINUTES)
    public void crawlPeriodic() {
        Spider.create()
                .addUrl("https://news.example.com/latest")
                .threads(3)
                .run();
    }
}
```

### 编程式定时（SchedulerProvider）

```java
import com.chua.common.support.task.scheduler.SchedulerProvider;
import com.chua.common.support.task.scheduler.JdkSchedulerProvider;
import com.chua.common.support.task.scheduler.Trigger;
import com.chua.common.support.task.scheduler.CronTrigger;
import com.chua.common.support.task.scheduler.FixedTrigger;

public class ScheduledCrawler {

    public static void main(String[] args) {
        SchedulerProvider scheduler = new JdkSchedulerProvider();

        // Cron 表达式：每天中午 12 点
        Trigger cronTrigger = new CronTrigger("0 0 12 * * ?");
        scheduler.schedule("daily-noon-spider", () -> {
            Spider.create()
                    .addUrl("https://news.example.com")
                    .run();
        }, cronTrigger);

        // 固定频率：每 10 分钟
        Trigger fixedTrigger = new FixedTrigger(10, TimeUnit.MINUTES);
        scheduler.schedule("periodic-10min-spider", () -> {
            Spider.create()
                    .addUrl("https://news.example.com/latest")
                    .run();
        }, fixedTrigger);
    }
}
```

> **注意**：`spider.run()` 是阻塞调用，会等待队列中的 URL 全部处理完成。因此任务执行期间同一爬虫任务不会重叠触发。

---

## 组件说明

### Fetcher（抓取器）— 拿内容回来

| 组件 | 说明 | 依赖 |
|------|------|------|
| `HttpFetcher` (`"http"`) | JDK HttpClient 实现，支持 GET/POST/PUT/DELETE | **无** |
| `PlaywrightFetcher` (`"playwright"`) | 浏览器渲染抓取，支持 JS 动态页面 | playwright |
| `AutoFetcher` (`"auto"`) | 自动降级，逐个尝试 SPI 实现直到成功 | **无** |

**职责：** 去目标 URL 拿原始内容，不关心内容是什么。

### Parser（解析器）— 拆成结构化数据

| 组件 | 说明 | 依赖 |
|------|------|------|
| `HtmlParser` (`"html"`) | JSoup HTML 解析，提取标题/正文/meta | jsoup |
| `PlaywrightParser` (`"playwright"`) | 浏览器渲染解析，提取 JS 后内容 | playwright |
| `AutoParser` (`"auto"`) | 自动降级，逐个尝试 SPI 实现 | **无** |

**职责：** 把拿回来的内容拆成结构化数据（标题、正文、meta）。

### Pipeline（回调）— 采集到数据后的处理

| 组件 | 说明 | 依赖 |
|------|------|------|
| `ConsolePipeline` (`"console"`) | 格式化打印到控制台 | **无** |
| Lambda Consumer | 直接传 Consumer 回调 | **无** |
| 自定义 Pipeline | 实现 `SpiderPipeline` 接口 | 自定义 |

**职责：** **采集到数据后的回调**，存文件、存 DB、打印、转发等。

---

## 快速开始

### 最小示例（内置全部默认）

```java
Spider.create()
    .addUrl("https://example.com")
    .run();
```

### 并发爬取

```java
Spider.create()
    .addUrl("https://example.com")
    .threads(5)                          // 5 个线程并发
    .site(SpiderSite.builder()
        .interval(500)                   // 请求间隔 500ms
        .retryTimes(3)                   // 失败重试 3 次
        .timeout(15000)                  // 超时 15 秒
        .maxDepth(2)                     // 最大深度 2
        .maxPages(100)                   // 最多 100 页
        .build())
    .pipeline(result -> System.out.println(result.getTitle()))
    .run();
```

### 自动降级（逐个尝试 SPI 实现）

```java
Spider.create()
    .addUrl("https://example.com")
    .fetcher("auto")                     // 自动找可用的 Fetcher
    .parser("auto")                      // 自动找可用的 Parser
    .run();
```

### 浏览器渲染（Playwright）

```java
Spider.create()
    .addUrl("https://spa-website.com")
    .fetcher("playwright")               // 使用 Playwright 渲染 JS
    .run();
```

---

## 采集流程详解

```
           ┌─────────────────────────────┐
           │  ① Scheduler.dequeue()      │  从队列取出下一个 URL
           └─────────────┬───────────────┘
                         │
           ┌─────────────▼───────────────┐
           │  ② Fetcher.fetch(request)   │  去 URL 拿原始 HTML/JSON
           │     ↓ statusCode=0?         │  失败→重试→降级
           └─────────────┬───────────────┘
                         │
           ┌─────────────▼───────────────┐
           │  ③ Parser.parse(response)   │  拆成 标题/正文/meta
           │     ↓ result==null?         │  不支持→跳过
           └─────────────┬───────────────┘
                         │
           ┌─────────────▼───────────────┐
           │  ④ AiParser.summarize()     │  AI 智能总结/提取（可选）
           └─────────────┬───────────────┘
                         │
           ┌─────────────▼───────────────┐
           │  ⑤ Pipeline.process(result) │  ★ 回调：用户拿到数据 ★
           │     ↓ 控制台/文件/DB/转发    │  ← 这是你关心的回调
           └─────────────┬───────────────┘
                         │
           ┌─────────────▼───────────────┐
           │  ⑥ LinkExtractor.extract()  │  提取新链接→入队→继续
           └─────────────────────────────┘
```

---

## 回调（Pipeline）用法

Pipeline 是**采集到数据后的回调**，支持三种方式：

### 方式1：Lambda 回调

```java
Spider.create()
    .addUrl("https://example.com")
    .pipeline(result -> {
        System.out.println("标题: " + result.getTitle());
        System.out.println("URL: " + result.getUrl());
        System.out.println("正文长度: " + result.getText().length());
        saveToDatabase(result);            // 你想怎么处理都行
    })
    .run();
```

### 方式2：SPI 名称

```java
Spider.create()
    .addUrl("https://example.com")
    .pipeline("console")                   // 内置控制台打印
    .run();
```

### 方式3：自定义类

```java
public class MyPipeline implements SpiderPipeline {
    @Override
    public void process(SpiderResult result) {
        // 存数据库 / 写文件 / 发消息队列
    }
}

Spider.create()
    .addUrl("https://example.com")
    .pipeline(new MyPipeline())
    .run();
```

---

## 内置配置

通过 `SpiderSite` 配置爬虫行为：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `domain` | null | 目标域名 |
| `userAgent` | Chrome 120 | User-Agent |
| `interval` | 1000ms | 请求间隔 |
| `retryTimes` | 3 | 失败重试次数 |
| `timeout` | 30000ms | 请求超时 |
| `maxDepth` | -1（不限） | 最大爬取深度 |
| `maxPages` | 0（不限） | 最大页面数 |
| `cookies` | null | Cookie |
| `headers` | empty | 自定义请求头 |
| `respectRobotsTxt` | false | 遵守 robots.txt |

---

## POJO 映射（@SpiderField + @SpiderAi）

通过注解直接将爬取结果映射为强类型 POJO，支持 CSS 选择器和 AI 两种提取方式。

### 定义 POJO

```java
import com.chua.spider.support.annotation.SpiderAi;
import com.chua.spider.support.annotation.SpiderField;

// 方式1：CSS 选择器提取（静态页面）
public class Article {

    @SpiderField(selector = "h1.article-title")
    private String title;

    @SpiderField(selector = "span.author", attr = "text")
    private String author;

    @SpiderField(selector = "meta[property=article:published_time]", attr = "content")
    private String publishDate;

    @SpiderField(selector = "div.content", attr = "html")
    private String contentHtml;

    // getter / setter ...
}

// 方式2：AI 提取（动态/复杂页面）
@SpiderAi("从页面中提取文章信息")
public class AiArticle {

    @SpiderField(ai = "文章标题")
    private String title;

    @SpiderField(ai = "作者名字")
    private String author;

    @SpiderField(ai = "发布时间")
    private String publishDate;

    // getter / setter ...
}

// 方式3：混合模式（CSS 优先，AI 补充）
@SpiderAi("提取文章的补充信息")
public class MixedArticle {

    @SpiderField(selector = "h1.title")       // CSS 选择器提取
    private String title;

    @SpiderField(ai = "文章摘要")               // AI 补充提取
    private String summary;

    // getter / setter ...
}
```

### 使用映射

```java
import static com.chua.spider.support.mapper.SpiderMappingPipeline.of;

// CSS 提取（不用 AI）
Spider.create()
    .addUrl("https://example.com/article")
    .as(Article.class, article -> {
        System.out.println("标题: " + article.getTitle());
        System.out.println("作者: " + article.getAuthor());
    })
    .run();

// AI 提取
Spider.create()
    .addUrl("https://example.com/article")
    .as(AiArticle.class, "openai", "sk-xxx", article -> {
        System.out.println("AI 提取的标题: " + article.getTitle());
        System.out.println("AI 提取的作者: " + article.getAuthor());
    })
    .run();
```

### @SpiderField 参数说明

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `selector` | CSS 选择器，如 `"h1.title"`、`"div#content"` | 空 |
| `attr` | 提取属性：`text`、`html`、`href`、`src`、`content` 等 | `text` |
| `ai` | AI 提取指令，如 `"文章标题"`、`"作者名字"` | 空 |
| `defaultValue` | 提取失败时的默认值 | 空 |

### @SpiderAi 参数说明

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `value` | AI 提取整体指令，作为 ChatClient 的 system prompt | 空 |
| `model` | AI 模型名称，如 `"gpt-4o"` | 空 |

---

## 扩展开发

### 自定义 Fetcher

```java
@Spi("my-fetcher")
public class MyFetcher implements SpiderFetcher {
    @Override
    public SpiderResponse fetch(SpiderRequest request) {
        // 你的抓取逻辑
    }
}

// 使用
Spider.create()
    .fetcher("my-fetcher")
    .addUrl("https://...")
    .run();
```

### 自定义 Pipeline

```java
@Spi("elasticsearch")
public class EsPipeline implements SpiderPipeline {
    @Override
    public void process(SpiderResult result) {
        // 写入 Elasticsearch
    }
}
```

---

## 依赖说明

| 依赖 | 必选 | 用途 |
|------|------|------|
| `utils-support-common-starter` | 是 | SPI、Deduplicator、ChatClient |
| `jsoup` | 否 | HTML 解析 / 链接提取 |
| `playwright` | 否 | 浏览器渲染抓取 |
| `okhttp` | 否 | HTTP 抓取备选 |
