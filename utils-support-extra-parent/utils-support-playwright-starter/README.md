# utils-support-playwright-starter

> utils 体系的 **Playwright 浏览器自动化模块**：一套 playwright 风格的 Java 门面（`Playwright` → `BrowserType` → `Browser` → `Page` → `ElementHandle`），底层经 **`Engine` SPI 双引擎**驱动——原生 Rust 无头浏览器动态库（headless_rust，Panama FFM 直连 Chromium CDP）优先，不可用时回退 playwright-Java 1.63.0；另提供 `Batch` 批量命令构建器与 API 请求原语。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.playwright.support`（门面）、`com.chua.playwright.support.engine`（引擎 SPI）

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

> 注意：截至当前源码状态，本模块**未列入** `utils-support-extra-parent` 的 `<modules>` 聚合清单（见第 6.2 节）。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | playwright-Java 风格的浏览器自动化门面 + `Engine` SPI 双引擎实现 | 不是 Spring Boot 自动装配模块：无 Bean、无配置属性、无自动配置类 |
| 引擎 | `NativeEngine`（Rust headless_rust 动态库，Panama FFM，CDP 直连 Chromium）优先，`JavaEngine`（playwright-Java 1.63.0）回退 | 不绑定单一实现：引擎由 SPI 装配 + 可用性校验决定（见第 5 节） |
| 浏览器 | 仅 Chromium（`BrowserType` 只有 `chromium()`） | 不支持 Firefox / WebKit（源码未提供） |
| 能力 | 启动浏览器、上下文/页面管理、导航、元素交互、内容读取、截图、`evaluate`、选择器查询与等待、`selectOption`、批量 `Batch`、API 请求原语 | 不含 PDF / HTML→PNG 的门面包装（定义在 `Engine` SPI 上）；不含测试断言框架 |
| 依赖 | 必选 common-starter 与 jackson-databind；playwright-java 为 `optional` 回退依赖 | 不依赖 Spring、数据库；不反向依赖任何上层业务 starter |

**判断口径**：凡属「用 Java 驱动 Chromium 完成页面自动化」这一件事，放本模块；通用 HTTP 客户端、通用 JSON、SPI 装配框架留在 common-starter。

### 1.3 模块构成

门面包 `com.chua.playwright.support`（10 个类，均标注 `@author CH`、`@since 4.0.0`）：

- `Playwright` —— 静态入口：`create()` / `isNative()` / `version()`；实例方法 `chromium()`。
- `BrowserType` —— `launch()` / `launch(Map options)`，仅 Chromium。
- `Browser` —— `newContext([options])` / `newPage()` / `handle()` / `close()`。
- `BrowserContext` —— `newPage()` / `handle()` / `close()`。
- `Page` —— 导航、交互、内容读取、截图、`evaluate`、选择器查询与等待、`selectOption`、`close()`。
- `ElementHandle` —— 元素句柄版 `click` / `dblclick` / `fill` / 读取 / `hover` / `screenshot` / `evaluate` / `dispose()`。
- `Response` —— 导航响应：`status()` / `url()`。
- `APIRequestContext` —— `get` / `post` / `put` / `delete`；内部类 `ApiResponse`（`status()` / `body()`）。
- `Batch` —— 批量命令构建器。
- `PlaywrightException` —— 模块统一运行时异常（`String` / `Throwable` / 双参三个构造器）。

引擎包 `com.chua.playwright.support.engine`（3 个文件）：

- `Engine` —— 引擎 SPI 接口，35 个句柄模型动作原语；内部类 `ResponseData(status, url)` / `ApiResponseData(status, body)`。
- `NativeEngine` —— `@Spi("native")`，Rust 原生引擎。
- `JavaEngine` —— `@SpiDefault`，playwright-Java 引擎。

资源：`src/main/resources/META-INF/extensions/com.chua.playwright.support.engine.Engine`（SPI 注册文件，仅此一个资源文件）。无测试目录、无其他配置文件。

---

## 2. 核心 API 与速查表

### 2.1 调用链

```text
Playwright.create()                     静态入口，选定引擎（原生优先）
   └── .chromium()                      BrowserType（仅 Chromium）
         └── .launch([options])         Browser；options 键：headless(默认 true) / executablePath / args
               └── .newPage()           Page（也可 .newContext([options]).newPage()）
                     └── gotoPage / click / fill / screenshot / evaluate / ...
```

### 2.2 常见能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 打开页面 | `Playwright.create().chromium().launch().newPage()` | 原生引擎可用则优先；`launch(Map)` 可传 `headless` / `executablePath` / `args` |
| 导航 | `page.gotoPage(url)`、`page.gotoPage(url, Map.of("timeout", 5000))` | 返回 `Response`（`status()` / `url()`）；无网络响应返回 `null`；另有 `reload()` / `goBack()` / `goForward()`（越界或失败返回 `null`） |
| 交互 | `page.click(selector[, options])`、`fill`、`type`、`press`、`check`、`uncheck`、`hover`、`dblclick` | `click` / `dblclick` / `fill` / `hover` / 读取类 / `screenshot` / `evaluate` 在 `ElementHandle` 上有元素句柄版（无需 selector） |
| 读取 | `page.title()`、`page.url()`、`textContent`、`innerText`、`innerHTML`、`getAttribute`、`inputValue` | 页面级 selector 或元素句柄均可 |
| 截图 | `page.screenshot()`、`page.screenshot(Map.of("fullPage", true))` | 返回 PNG 字节；选项当前仅支持 `fullPage` |
| 执行 JS | `page.evaluate("1 + 1")`、`page.evaluate(expression, arg)` | 返回 Map / List / 标量 / `null` |
| 等待与查询 | `page.waitForSelector(selector[, timeoutMs])`、`querySelector`、`querySelectorAll`、`selectOption(selector, values...)` | `waitForSelector` 默认 30s；`querySelector` 未找到返回 `null`；`selectOption` 返回选中值列表（空数组取消全部选择，字符串按 value 或 label 匹配） |
| 批量命令 | `new Batch().launch(true)...execute()` | 链式入队 `launch` / `newPage` / `gotoPage` / `click` / `fill` / `screenshot` / `evaluate` / `close` / `raw`，`execute()` 按入队顺序执行并返回结果列表 |

另有：`Playwright.isNative()` 判断当前是否原生引擎；`Playwright.version()` 返回引擎版本串（如 `1.63.0-native` / `1.63.0-java`）。

### 2.3 方法级 options 键（源码中出现的全部键）

| 位置 | 键 | 类型 / 默认 | 说明 |
| --- | --- | --- | --- |
| `BrowserType.launch` | `headless` | Boolean，默认 `true` | 是否无头运行 |
| `BrowserType.launch` | `executablePath` | String，默认 `null` | 浏览器可执行文件路径；原生模式留空用系统默认查找 |
| `BrowserType.launch` | `args` | List&lt;String&gt;，默认 `null` | 浏览器启动参数 |
| `Page.gotoPage` | `timeout` | Number（毫秒） | 导航超时 |
| `Page.screenshot` | `fullPage` | Boolean | 是否整页截图 |
| `Engine.printPageToPdf` | `format` / `landscape` / `printBackground` / `scale` / `pageRanges` | — | PDF 选项（`Page` 门面未包装该方法，见 5.3 节） |

> 两个引擎对部分 options 的现状：`Browser.newContext(options)` 在 JavaEngine 侧忽略 options、原生侧暂不生效；`Page.click(selector, options)` 的 options 两个引擎当前都未实际使用。

### 2.4 Batch 要点

- `new Batch()` 绑定 `Playwright` 当前全局引擎（与 `Playwright.create()` 同一选择结果）。
- `stopOnError(boolean)`：默认 `true`，首个命令失败即抛 `RuntimeException`；置 `false` 时失败项以 `{"error": 消息}` 记入结果并继续。
- `launch` / `newPage` 的执行结果形如 `{"handle": <句柄>}`；**句柄在执行前未知**，跨命令传递句柄需先 `execute()` 取结果，再构建新批次继续入队。
- 支持的 action 白名单：`launch` / `newPage` / `goto` / `click` / `fill` / `screenshot` / `evaluate` / `close`（`Batch.dispatch` 与两个引擎的 `batch` 实现一致）；未知 action 抛 `UnsupportedOperationException`（Batch）或 `PlaywrightException`（JavaEngine）。
- `screenshot` 项在原生引擎结果中解码为 `byte[]`。

---

## 3. 快速开始

### 3.1 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-playwright-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例（门面）

```java
import com.chua.playwright.support.Browser;
import com.chua.playwright.support.Page;
import com.chua.playwright.support.Playwright;

import java.util.Map;

public class PlaywrightDemo {
    public static void main(String[] args) {
        // 1) 入口：原生引擎可用则优先，否则回退 playwright-Java
        Playwright playwright = Playwright.create();
        System.out.println("engine=" + (Playwright.isNative() ? "native" : "java")
                + ", version=" + Playwright.version());

        // 2) 启动 Chromium（headless 默认 true），打开页面
        Browser browser = playwright.chromium().launch(Map.of("headless", true));
        Page page = browser.newPage();

        // 3) 导航、执行脚本、读取、截图
        page.gotoPage("https://example.com", Map.of("timeout", 10000));
        System.out.println(page.title());
        System.out.println(page.textContent("h1"));
        System.out.println(page.evaluate("1 + 1"));            // 2
        byte[] png = page.screenshot(Map.of("fullPage", true));

        // 4) 释放
        page.close();
        browser.close();
    }
}
```

### 3.3 批量示例（Batch）

```java
import com.chua.playwright.support.Batch;

import java.util.List;
import java.util.Map;

public class BatchDemo {
    public static void main(String[] args) {
        // 第一轮：启动浏览器
        Batch round1 = new Batch();
        round1.launch(true);                                    // 命令下标 0
        List<Object> r1 = round1.execute();
        long browserHandle = ((Number) ((Map<?, ?>) r1.get(0)).get("handle")).longValue();

        // 第二轮：拿到浏览器句柄后开页面
        Batch round2 = new Batch();
        round2.newPage((int) browserHandle);
        long pageHandle = ((Number) ((Map<?, ?>) round2.execute().get(0)).get("handle")).longValue();

        // 第三轮：导航 + 截图（screenshot 项结果为 byte[]）
        Batch round3 = new Batch();
        round3.gotoPage((int) pageHandle, "https://example.com");
        round3.screenshot((int) pageHandle);
        List<Object> r3 = round3.execute();
    }
}
```

---

## 4. 配置项

**无。** 依据：

- 本模块 `pom.xml` 未声明任何配置属性；
- 13 个源码文件中没有读取任何系统属性、环境变量，也没有 `@ConfigurationProperties` 或自动配置类；
- 所有可调参数均为**方法级 `Map<String, Object>` options**（键见 2.3 节），不落任何 application 配置文件。

---

## 5. SPI 扩展点

### 5.1 SPI 接口与注册

| 项 | 值 |
| --- | --- |
| SPI 接口 | `com.chua.playwright.support.engine.Engine`（35 个句柄模型动作原语：`launch` / `newContext` / `newPage` 等返回 `long` 句柄，后续动作按句柄转发） |
| 实现 1 | `NativeEngine`，`@Spi("native")`，Rust `headless_rust` 动态库（Panama FFM） |
| 实现 2 | `JavaEngine`，`@SpiDefault`，playwright-Java 1.63.0 |
| 注册文件 | `META-INF/extensions/com.chua.playwright.support.engine.Engine`，内容两行：`native=com.chua.playwright.support.engine.NativeEngine` 与 `java=com.chua.playwright.support.engine.JavaEngine` |
| 装载方 | common-starter 的 `ServiceProvider`（支持 `别名=实现类全限定名` 写法） |

### 5.2 引擎选择逻辑（`Playwright` 私有 `createEngine`）

```text
ServiceProvider.of(Engine.class).getExtension("native")
   → 是 NativeEngine 且 isAvailable()（原生库抽取 + 符号解析成功）→ 用原生
否则 ServiceProvider.of(Engine.class).getSpiService()（@SpiDefault → JavaEngine）
否则直接 new JavaEngine()
```

- `Playwright.isNative()` 反映当前生效引擎（实例经双重检查锁缓存，进程内单例）。
- 原生库加载失败时 `NativeEngine.getLoadError()` 可取失败原因；告警日志提示自行添加依赖 `com.chua:utils-support-native-headless:4.0.0.43`。
- `NativeEngine` 信封协议：请求 `{"action":...,"handle":...,"params":{...}}`，响应 `{"ok":...}` 或 `{"error":...}`；错误统一转 `PlaywrightException`。FFM 符号：`pw_call` / `pw_free_string` / `pw_version`；响应读取窗口上限 512MB。
- `JavaEngine` 维护句柄 → Playwright 对象注册表；`apiRequest` 用 JDK `HttpClient`（跟随重定向 NORMAL、连接/请求超时 30s、JSON Content-Type）执行真实网络请求；动作名映射 `apiget/get`、`apipost/post`、`apiput/put`、`apidelete/delete`、`apihead/head`、`apipatch/patch`、`apioptions/options`，其余抛 `PlaywrightException`。

### 5.3 Engine 上的两个渲染原语（`Page` 门面未包装）

- `String printPageToPdf(long pageHandle, Map options)` —— 页面渲染为 PDF，返回 base64 字符串；options 键：`format` / `landscape` / `printBackground` / `scale` / `pageRanges`（原生侧经 Chrome DevTools 协议 `Page.printToPDF`，JavaEngine 走 playwright-Java `Page.pdf`）。
- `String convertHtmlToPng(long pageHandle, String html)` —— HTML 经 data: URI 加载后截图，返回 base64 PNG。

需要使用时，可通过 `ServiceProvider.of(Engine.class)` 直接取得引擎实例调用。

### 5.4 如何扩展一个新引擎

```java
import com.chua.common.support.spi.annotations.Spi;
import com.chua.playwright.support.engine.Engine;

@Spi("my-engine")
public class MyEngine implements Engine {
    // 实现 launch / gotoPage / click / screenshot / batch / version 等全部 35 个方法
}
```

并在 `META-INF/extensions/com.chua.playwright.support.engine.Engine` 追加一行：

```text
my-engine=com.example.MyEngine
```

注意：`Playwright` 门面当前只按 `native` 别名 + `@SpiDefault` 默认实现做选择；新增实现若想被门面自动选中，需成为默认实现或自行改造选择逻辑。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（根 pom `maven.compiler.source/target=25`、`java.version=25`；`enable.preview.jvm.args=--enable-preview`）。
- 本模块 surefire 的 `argLine` 为 `${enable.preview.jvm.args} --enable-native-access=ALL-UNNAMED`：FFM（`java.lang.foreign`）自 Java 24 起属受限 API，测试 JVM 需显式授权（pom 注释原文）。
- 源码 UTF-8、无 BOM；无测试目录。

### 6.2 聚合状态与构建命令

`utils-support-extra-parent/pom.xml` 的 `<modules>` 清单当前**不包含**本模块，即本模块不随 extra-parent 聚合构建。单独构建在本目录执行：

```bash
cd utils-support-parent-starter/utils-support-extra-parent/utils-support-playwright-starter
mvn clean package
```

### 6.3 依赖清单

| 依赖 | Scope | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile（`${project.version}`） | `ServiceProvider` SPI 装配、`Wraps` 集合、`NativeLoader` / `NativeUtils` 原生库抽取、`StringUtils` |
| `com.fasterxml.jackson.core:jackson-databind` | compile（版本由父 POM 托管） | 原生引擎信封协议 JSON 序列化 |
| `com.microsoft.playwright:playwright` `1.63.0` | compile，`<optional>true</optional>` | `JavaEngine` 回退模式（pom 注释：playwright-java 双模式回退） |
| `org.projectlombok:lombok` | 本模块 pom 未显式声明；版本由根 POM 托管（`1.18.48`） | `@Slf4j` / `@ToString` |
| `com.chua:utils-support-native-headless` `4.0.0.43` | 运行期可选 | `NativeEngine` 所需 Rust 动态库的载体依赖（坐标来自加载失败告警日志；缺失时回退 JavaEngine） |

---

## 7. 边界：这些能力不在本模块

| 能力 | 在哪里 |
| --- | --- |
| playwright-Java 官方全量 API（网络路由拦截、事件监听、iframe / worker、下载、storageState、trace 等） | 第三方库 `com.microsoft.playwright:playwright`；本模块门面仅包装上文列出的子集 |
| Firefox / WebKit 浏览器 | 源码未提供——`Playwright` 仅暴露 `chromium()` |
| PDF 渲染 / HTML→PNG 的门面方法 | 定义在 `Engine` SPI（`printPageToPdf` / `convertHtmlToPng`），`Page` 未包装 |
| `APIRequestContext` 的公开创建入口 | 源码未提供——其构造器为包级私有且模块内无创建点；底层原语是 `Engine.newAPIRequest()` / `apiRequest(action, url, body)` |
| 通用 HTTP 客户端能力 | common-starter 工具体系 / `utils-support-okhttp-starter` 等；`JavaEngine` 内部仅用 JDK `HttpClient` 承接 `apiRequest` |
| Spring Boot 自动装配 / application 配置属性 | 无——本模块名为 starter，实为纯 Java 库 + SPI 注册文件 |
| 浏览器二进制的下载与管理 | playwright-Java 自带 driver 机制；原生 Rust 库由 `utils-support-native-headless` 提供 |
