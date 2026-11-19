# utils-support-kitesurf-starter

Kitesurf 无状态浏览器（Cloudflare Workers）Java 客户端：把任意 URL 渲染为图片（PNG）、PDF 或 JS 执行后的 HTML，并以 MCP 客户端形式暴露截图 / PDF / 渲染 HTML 三个工具。

- **Maven 坐标**：`com.chua:utils-support-kitesurf-starter`，版本随父模块 `4.0.0.51`
- **JDK**：25（仓库根 `java.version=25`；本模块 API 自 `4.0.0.46` 引入）
- **包名**：`com.chua.kitesurf.support`（`renderer` 渲染客户端、`agent` MCP 提供者）

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界（这些能力不在本模块）](#边界这些能力不在本模块)

## 它是什么 / 不是什么

| 它是 | 它不是 |
| --- | --- |
| Kitesurf 在线端点（默认 `https://kitesurf.dev`，免账号）的纯 HTTP 客户端 | 本地浏览器引擎（不启动 Chrome / Playwright / Jvppeteer / CDP 进程或容器） |
| `KitesurfRenderer`：URL → PNG / PDF / 渲染后 HTML 的静态工具 | MCP Server（本模块只提供 MCP 客户端侧的 `McpProvider` 实现） |
| `KitesurfMcpProvider`：SPI 名 `kitesurf` 的 MCP 工具提供者，暴露 3 个浏览器工具 | 元素级交互库（无点击、键盘、等待选择器、反爬等能力） |
| 无状态、零本地资源占用的渲染通道 | Spring Boot 自动配置 starter（无 Spring 依赖；名字里的 starter 是本仓库命名惯例） |

## 核心 API 与速查表

两个主类，均在包 `com.chua.kitesurf.support` 下：

| 入口 | 用途 | 备注 |
| --- | --- | --- |
| `KitesurfRenderer.screenshot(url, outFile)` | URL → PNG，写入文件 | 重载带 `Consumer<RenderConfig>` 可配服务地址 / 超时 / 请求头 |
| `KitesurfRenderer.pdf(url, outFile)` | URL → PDF，写入文件 | 同上 |
| `KitesurfRenderer.html(url)` | URL → JS 执行后的渲染 HTML 文本 | 返回 `String` |
| `KitesurfRenderer.fetch(config, action, url)` | 直调端点返回原始字节 | `action` 取 `screenshot` / `pdf` / `html` |
| `KitesurfRenderer.RenderConfig` | 渲染配置 | `setBaseUrl` / `setTimeout(Duration)` / `setExtraHeaders(Map)`，链式返回自身 |
| `KitesurfMcpProvider`（SPI 名 `kitesurf`） | MCP 工具提供者 | 实现 `McpProvider`，`create()` 返回无状态单例 `KitesurfMcpClient` |
| `KitesurfMcpProvider.resolveBaseUrl()` | 解析生效服务地址 | 系统属性 > 环境变量 > 默认在线地址 |
| `KitesurfMcpProvider.KitesurfMcpClient.listTools()` / `callTool(...)` | MCP 工具清单与调用 | 工具：`kitesurf_screenshot` / `kitesurf_pdf` / `kitesurf_html`，参数均为 `url`（必填） |

`KitesurfRenderer` 对应的在线端点（GET，`url` 参数经 UTF-8 URL 编码）：

| 端点 | 产出 |
| --- | --- |
| `GET {base}/screenshot?url=...` | PNG 图片字节 |
| `GET {base}/pdf?url=...` | PDF 文档字节 |
| `GET {base}/html?url=...` | 渲染后 HTML 文本（UTF-8） |

失败行为（源码事实）：

- `url` 为空 → 抛 `IllegalArgumentException`（「url 不能为空」）；
- HTTP 状态码非 2xx → 抛 `RuntimeException`（含状态码与 action/url）；
- 网络 / 中断异常 → 包装为 `RuntimeException` 抛出，中断时恢复线程中断标志；
- 写文件失败 → 抛 `RuntimeException`（「写入文件失败」）；输出文件父目录不存在时自动创建。

MCP 工具返回内容（源码事实）：

- `kitesurf_screenshot`：`mimeType=image/png`、`base64`、`onlineUrl`（在线直链）；
- `kitesurf_pdf`：`mimeType=application/pdf`、`base64`；
- `kitesurf_html`：渲染后 HTML 文本；
- 缺 `url` 参数、未知工具名、调用异常 → 返回 `McpToolResult.error(...)`。

## 快速开始

引入依赖（见 [构建与依赖](#构建与依赖)）后，渲染三件套都是静态方法，开箱即用：

```java
import com.chua.kitesurf.support.renderer.KitesurfRenderer;

// 1) URL → PNG（默认在线服务 https://kitesurf.dev，免账号）
KitesurfRenderer.screenshot("https://example.com", "D:/tmp/example.png");

// 2) URL → PDF
KitesurfRenderer.pdf("https://example.com", "D:/tmp/example.pdf");

// 3) URL → JS 执行后的渲染 HTML
String html = KitesurfRenderer.html("https://example.com");
```

自建 Browser Run（`browser=kitesurf`）时，用 `RenderConfig` 回调改指向并按需加鉴权头：

```java
import java.time.Duration;
import java.util.Map;
import com.chua.kitesurf.support.renderer.KitesurfRenderer;

KitesurfRenderer.screenshot("https://example.com", "D:/tmp/example.png",
        cfg -> cfg.setBaseUrl("https://my-browser-run.example.com")
                  .setTimeout(Duration.ofSeconds(30))
                  .setExtraHeaders(Map.of("Authorization", "Bearer <token>")));
```

MCP 工具侧无需手工构造：模块通过标准 SPI 注册了 `KitesurfMcpProvider`（见 [SPI 扩展点](#spi-扩展点)），接入方按 `McpProvider` 契约拿到 `KitesurfMcpClient` 后，直接 `listTools()` / `callTool(...)` 即可，例如：

```java
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.kitesurf.support.agent.KitesurfMcpProvider;
import com.chua.kitesurf.support.agent.KitesurfMcpProvider.KitesurfMcpClient;

KitesurfMcpClient client = new KitesurfMcpProvider().create();
client.init();
client.listTools().forEach(t -> System.out.println(t.getName()));

McpToolResult result = client.callTool(new McpToolCall(
        "kitesurf_screenshot", Map.of("url", "https://example.com")));
```

> `McpProvider` / `McpClient` / `McpToolCall` / `McpToolDescriptor` / `McpToolResult` 契约均由 `utils-support-common-starter` 的 `com.chua.common.support.ai.mcp` 包定义；也可经 common-starter 的 SPI 发现机制按名称 `kitesurf` 获取本提供者，无需直接 `new`。

## 配置项

本模块无 Spring Boot 配置前缀、无 `application.properties` 约定（依据：`pom.xml` 仅依赖 `utils-support-common-starter`，无 Spring 相关依赖；源码中也没有自动配置类）。运行期可配置项全部来自源码，共三类：

| 配置途径 | 键 / 字段 | 生效方式 | 默认值 |
| --- | --- | --- | --- |
| 系统属性 | `kitesurf.base-url`（常量 `KitesurfMcpProvider.BASE_URL_PROPERTY`） | MCP 侧 `resolveBaseUrl()` 优先级最高 | — |
| 环境变量 | `KITESURF_BASE_URL`（常量 `KitesurfMcpProvider.BASE_URL_ENV`） | 系统属性缺省时生效 | — |
| 代码级 | `RenderConfig.setBaseUrl` / `setTimeout` / `setExtraHeaders` | 仅影响当次渲染调用 | `baseUrl=https://kitesurf.dev`（`KitesurfRenderer.DEFAULT_BASE_URL`）、`timeout=90 秒`、`extraHeaders=null` |

## SPI 扩展点

- 模块在 `src/main/resources/META-INF/services/com.chua.common.support.ai.mcp.McpProvider` 中注册了实现类 `com.chua.kitesurf.support.agent.KitesurfMcpProvider`，SPI 名为 `kitesurf`（类上标注 `@Spi("kitesurf")`）。
- `KitesurfMcpProvider` 实现 `McpProvider` 契约：`name()` 返回 `"kitesurf"`，`create()` 返回无状态单例 `KitesurfMcpClient`（纯 HTTP 调用，不持有本地进程 / 容器 / 连接）。
- `KitesurfMcpClient` 实现 `McpClient` 契约：`init()` / `isInitialized()` / `listTools()` / `callTool(McpToolCall)`。
- 服务地址解析顺序（`resolveBaseUrl()`）：系统属性 `kitesurf.base-url` > 环境变量 `KITESURF_BASE_URL` > `KitesurfRenderer.DEFAULT_BASE_URL`。
- 本模块不提供其他 SPI 扩展点；`McpProvider` / `McpClient` 等契约接口本身由 `utils-support-common-starter` 定义。

## 构建与依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-kitesurf-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

- 父模块：`com.chua:utils-support-network-parent:4.0.0.51`（聚合于 `utils-support-parent-starter`）。
- 唯一直接依赖：`com.chua:utils-support-common-starter`（同版本），无其他第三方依赖。
- 源码结构：

```text
utils-support-kitesurf-starter/
├── pom.xml
└── src/main/
    ├── java/com/chua/kitesurf/support/
    │   ├── renderer/KitesurfRenderer.java      # 渲染客户端 + RenderConfig
    │   └── agent/KitesurfMcpProvider.java      # MCP 提供者 + KitesurfMcpClient
    └── resources/META-INF/services/
        └── com.chua.common.support.ai.mcp.McpProvider
```

本地构建（在仓库根或本目录）：

```bash
mvn -o -q compile -DskipTests
```

## 边界（这些能力不在本模块）

- **本地浏览器**：不内置也不启动任何浏览器进程 / 容器；渲染完全依赖远端 Kitesurf 服务（在线公共端点或自建 Browser Run）。
- **元素级交互**：不支持点击、键盘输入、等待选择器、Cookie 注入、反爬绕过等浏览器自动化能力（源码未提供）。
- **MCP Server**：本模块只实现 `McpProvider`（客户端侧工具提供者），不承载 MCP Server。
- **Spring Boot 自动配置**：无自动配置类、无配置属性类、无 `spring-boot-configuration-processor` 元数据（源码未提供）。
- **重试 / 代理 / 连接池**：渲染调用无内置重试、代理与连接池配置；需要鉴权时通过 `RenderConfig.setExtraHeaders` 携带（如 `Authorization`），鉴权本身不在本模块实现。
- **页面渲染规则定制**：视口、UA、等待策略、PDF 页边距等渲染参数不可调；端点仅接受 `url` 一个查询参数（源码事实）。
