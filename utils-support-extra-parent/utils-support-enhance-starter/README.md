# utils-support-enhance-starter

> utils 体系的**增强扩展模块**：emoji 工具集、HTTP Server 安全过滤器族、按天备份策略 SPI 实现。能力以「契约在 `utils-support-common-starter`、实现在本模块」的 SPI 方式提供。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.enhance.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. Emoji 增强（emoji 包）](#3-emoji-增强emoji-包)
- [4. 请求安全过滤器（network.server.filter.security 包）](#4-请求安全过滤器networkserverfiltersecurity-包)
- [5. 按天备份策略（backup 包）](#5-按天备份策略backup-包)
- [6. 快速开始](#6-快速开始)
- [7. 配置项](#7-配置项)
- [8. SPI 扩展点](#8-spi-扩展点)
- [9. 构建与依赖](#9-构建与依赖)
- [10. 边界：这些能力不在本模块](#10-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-enhance-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | common-starter 契约的增强实现集合：emoji 工具、安全过滤器、备份策略 | 不是 Spring Boot starter（源码中无自动配置类、无 `@ConfigurationProperties`） |
| Emoji | 提供 emoji 的注册、查找、Trie 匹配、HTML/短码互转工具 | 不内置 emoji 词库数据（`EmojiManager` 初始为空，需自行注册） |
| 过滤器 | `ServerFilter` 的 11 个安全/治理实现，按 SPI 名挂载到 common-starter 的 Server | 不提供 HTTP 服务端与请求/响应模型本身 |
| 依赖 | 直接依赖仅 `utils-support-common-starter` | 不依赖 Spring、Servlet、数据库驱动或第三方协议栈 |

### 1.3 规模

- Java 源文件 **17** 个顶层类：`emoji` 包 5 个、`network.server.filter.security` 包 11 个、`backup` 包 1 个。
- SPI 扩展声明文件 **2** 个（`META-INF/extensions/`）。
- 内置 GraalVM `native-image` 配置：`resource-config.json` 打包 `META-INF/extensions/.*`；`reflect-config.json` 为占位文件。

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 查找/判断 emoji | `EmojiUtils.getEmoji(code)` / `getForAlias(alias)` / `isEmoji(code)` | 支持 Unicode、短码、HTML 实体、别名 |
| emoji ↔ HTML/短码互转 | `EmojiUtils.htmlify` / `hexHtmlify` / `shortCodify` / `emojify` | 文本级转换与还原 |
| 注册自定义 emoji | `EmojiManager.addEmoji(Emoji)` | 同步写入数据表、别名索引与前缀树 |
| emoji 前缀匹配 | `EmojiTrie.isEmoji(chars, start, end)` | 返回 `Matches`：exact / prefix / impossible |
| 按天备份 | `ServiceProvider.of(BackupStrategy.class).getExtension("daily")` | 得到 `EnhanceBackupStrategyProvider` |
| 服务端认证/签名 | SPI 名 `auth` / `signature` | 请求头凭证校验 + HMAC-SHA256/MD5 防篡改 |
| 服务端限流 | SPI 名 `qps-limit` / `ip-rate-limit` / `dos` | 全局滑动窗口 / 按 IP 令牌桶 / 按 IP 固定窗口 |
| 服务端防护 | SPI 名 `xss` / `ssrf` / `path-traversal` / `crawler` | XSS 净化 / SSRF 校验 / 目录穿越 / 爬虫识别 |
| 跨域与请求日志 | SPI 名 `cors-header` / `request-log` | CORS 响应头 + 请求耗时日志 |

---

## 3. Emoji 增强（`emoji` 包）

| 类 | 职责 |
| --- | --- |
| `Emoji` | 数据模型：`unicode`、`aliases`（别名列表）、`hexHtml`、`decimalHtml`、`description`、`emoticons`（表情符号列表）；`getHexHtml()` / `getDecimalHtml()` 未显式赋值时按 `unicode` 逐字符惰性计算 |
| `AbstractEmoji` | 抽象基类：短码与 HTML 实体的正则模式（`SHORT_CODE_PATTERN`、`HTML_ENTITY_PATTERN` 等）与 `htmlHelper` 转换辅助 |
| `EmojiManager` | 全局管理器：`data()` 数据表、`EMOJIS_BY_ALIAS` 别名索引、`EMOJI_TRIE` 前缀树（深度 10）、`addEmoji(Emoji)` 注册、`getEmoticonRegexPattern()` |
| `EmojiTrie` | 前缀树：`addEmoji`、`isEmoji(char[], start, end)`、`getEmoji(String)`；匹配结果 `Matches.exact()` / `prefix()` / `impossible()` |
| `EmojiUtils` | 静态工具门面：查找、判断、`emojify` / `htmlify` / `hexHtmlify` / `shortCodify` / `countEmojis` / `removeAllEmojis` |

行为要点（与源码一致）：

- **无内置词库**：`EmojiManager` 静态初始化为空列表，`getEmoticonRegexPattern()` 默认返回空正则。必须先 `addEmoji` 注册，查找与转换才有结果。
- `getEmoji(code)` 的匹配范围：`unicode`（即 `getEmojiChar()`）、`hexHtml`、`decimalHtml`、别名、表情符号；入参形如 `:alias:` 的短码会先剥离冒号再按别名匹配。
- `htmlify` / `hexHtmlify` 按 **UTF-16 代码单元**逐字符生成实体：代理对 emoji（如 `😀`）输出 `&#55357;&#56832;` / `&#xd83d;&#xde00;`，而不是整码点的 `&#128512;`。
- `shortCodify` 用每个 emoji 的**第一个别名**生成 `:alias:`。
- `countEmojis(text)` 先 `htmlify` 再逐实体判断是否为已注册 emoji。

---

## 4. 请求安全过滤器（`network.server.filter.security` 包）

11 个过滤器均实现 common-starter 的 `com.chua.common.support.network.server.filter.ServerFilter`，通过 `META-INF/extensions/` 按名字注册：

| SPI 名 | 实现类 | `getOrder()`（越小越先） | 生效条件（`isEnabled()`） |
| --- | --- | --- | --- |
| `cors-header` | `CorsServerFilter` | 5 | 任一 `cors.*` 配置显式给出 |
| `request-log` | `LoggingServerFilter` | 7 | `requestlog.enabled=true` |
| `path-traversal` | `PathTraversalServerFilter` | 8 | 始终启用 |
| `auth` | `AuthServerFilter` | 10 | `auth.tokens` 非空 |
| `ssrf` | `SsrfServerFilter` | 12 | 任一 `ssrf.*` 配置显式给出 |
| `signature` | `SignatureServerFilter` | 15 | `signature.secret` 非空 |
| `dos` | `DosServerFilter` | 20 | 任一 `dos.*` 配置给出且阈值 > 0 |
| `qps-limit` | `QpsServerFilter` | 22 | `qps.max` > 0 |
| `ip-rate-limit` | `IpRateLimitServerFilter` | 25 | 任一 `ipRateLimit.*` 配置给出且值 > 0 |
| `crawler` | `CrawlerServerFilter` | 28 | 任一 `crawler.*` 配置给出（再由 `crawler.enabled` 控制） |
| `xss` | `XssServerFilter` | 30 | 始终启用 |

> 设计口径：**未显式配置不挂载**。除 `xss`、`path-traversal`（未覆写 `isEnabled()`，走接口默认 `true`）外，其余过滤器在配置缺失时整体退出过滤器链，避免默认策略拦掉正常业务流量。

各过滤器行为与配置键（均经 `ServerFilterConfig#getInitParameter` 读取）：

| 过滤器 | 行为 | 配置键（默认值） | 拦截响应 |
| --- | --- | --- | --- |
| `CorsServerFilter` | 写 `Access-Control-Allow-*` 响应头；`OPTIONS` 预检直接 204；仅 HTTP 协议（`supportProtocols()` 覆写为 `ProtocolType.HTTP`） | `cors.allowOrigin`(`*`)、`cors.allowMethods`(`GET,POST,PUT,DELETE,PATCH,OPTIONS`)、`cors.allowHeaders`(`*`)、`cors.maxAge`(`3600`)、`cors.allowCredentials`(`false`) | — |
| `LoggingServerFilter` | SLF4J 记录 `远端地址 方法 路径 -> 状态码 (耗时ms)` | `requestlog.enabled` | — |
| `PathTraversalServerFilter` | 原始路径与 URL 解码后路径按 `/`、`\` 分段检查 `..`；配置根目录时再做规范化越界校验 | `pathTraversal.root`（可选） | 400 |
| `AuthServerFilter` | 校验认证头；白名单为空时跳过 token 比对；排除路径按前缀匹配 | `auth.header`(`Authorization`)、`auth.tokens`、`auth.excludePaths` | 缺头 401 / 不在白名单 403 |
| `SsrfServerFilter` | 校验 `Referer`、`Origin`：命中内网地址（`localhost`/`127.0.0.1`/`::1`/回环/站点本地/链路本地）或不在白名单（支持 `xxx.allowed` 子域后缀匹配）则拦截 | `ssrf.allowedDomains`、`ssrf.blockInternal`(`true`) | 403 |
| `SignatureServerFilter` | 对请求体按 `HMAC-SHA256`（Base64）或 `MD5`（hex）计算签名，与签名头比对；排除路径按前缀匹配 | `signature.secret`（必填）、`signature.algorithm`(`HMAC-SHA256`)、`signature.header`(`X-Signature`)、`signature.excludePaths` | 缺签名 401 / 不匹配 403 |
| `DosServerFilter` | 按 IP 固定窗口计数限流 | `dos.maxRequests`(`100`)、`dos.windowSeconds`(`60`) | 429 |
| `QpsServerFilter` | 全局滑动窗口（1 秒）计数限流，不区分客户端 | `qps.max`(`1000`) | 429 |
| `IpRateLimitServerFilter` | 按 IP 令牌桶限流，桶按时间补充令牌 | `ipRateLimit.bucketCapacity`(`50`)、`ipRateLimit.refillRate`(`10`) | 429 |
| `CrawlerServerFilter` | ① User-Agent 命中爬虫关键词（内置搜索引擎爬虫、社交预取、`curl`/`wget`/`python-requests`/`okhttp` 等及通配 `bot`/`spider`/`crawler`，可追加）按状态码拦截；② 按「IP+方法+URI」检测窗口内周期性重复请求，**仅记录告警日志不拦截**；自带过期记录清理线程，`destroy()` 释放 | `crawler.enabled`(`true`)、`crawler.blockEnabled`(`true`)、`crawler.blockStatus`(`403`)、`crawler.customUaKeywords`、`crawler.periodEnabled`(`true`)、`crawler.periodMinTimes`(`5`)、`crawler.periodWindowSeconds`(`60`)、`crawler.periodMaxIntervalRatio`(`1.5`)、`crawler.cleanupIntervalSeconds`(`300`) | 默认 403（可配） |
| `XssServerFilter` | 对文本类请求体（content-type 含 `text`/`json`/`xml`/`form-urlencoded`）净化：移除 `<script>...</script>` 块与 `onXXX="..."` 事件属性（类注释另给出 `< > " ' &` 的转义规则）；净化结果写入请求属性 `xss.sanitizedBody` | 无 | — |

客户端 IP 解析（`dos`、`ip-rate-limit`、`crawler` 共用同一逻辑）：`X-Forwarded-For` 首段 → `X-Real-IP` → `request.getRemoteAddress()`。

拦截响应统一为 JSON 体，如 `{"error":"Forbidden","message":"..."}`；状态码：400（非法路径）、401（缺凭证/缺签名）、403（凭证无效/签名失败/SSRF/爬虫）、429（三类限流）。

---

## 5. 按天备份策略（`backup` 包）

| 类 | 说明 |
| --- | --- |
| `EnhanceBackupStrategyProvider` | 以 `@Spi("daily")` 注册的 `BackupStrategy` 实现，是本仓 `daily` 备份策略的唯一注册来源 |

行为要点：

- 实现 `BackupStrategy` 的四个方法：`type()` 返回 `daily`；`execute(BackupConfig)`、`cleanExpired(BackupConfig)`、`listBackups(BackupConfig)` 当前**全部委托**给 common-starter 的 `DefaultDailyBackupStrategy`。
- 类注释声明的增强点（日志记录、备份校验、`{backupDir}/{yyyy-MM-dd}/{sourceDirName}/` + `manifest.json` + `archive/{yyyy-MM-dd}.zip` 的目录结构）为设计目标；就当前实现而言，运行行为与默认按天策略一致。
- 入参 `BackupConfig` 定义在 common-starter（`@Builder`）：`backupDir`、`sourceDir`、`retentionDays`（默认 30）、`compressArchives`（默认 `true`）、`includePattern`、`excludePattern`。

---

## 6. 快速开始

### 6.1 Emoji：注册、查询、互转

```java
import com.chua.enhance.support.emoji.Emoji;
import com.chua.enhance.support.emoji.EmojiManager;
import com.chua.enhance.support.emoji.EmojiUtils;
import java.util.Arrays;

// 1. 本模块不内置词库，先注册
Emoji emoji = new Emoji();
emoji.setUnicode("😀");
emoji.setAliases(Arrays.asList("grinning", "smile"));
EmojiManager.addEmoji(emoji);

// 2. 查找与判断
EmojiUtils.isEmoji("😀");          // true
EmojiUtils.getForAlias("smile");   // 上面注册的 Emoji
EmojiUtils.getEmoji(":grinning:"); // 短码/别名查找

// 3. 互转（HTML 实体按 UTF-16 代码单元逐字符生成）
EmojiUtils.htmlify("😀");          // "&#55357;&#56832;"
EmojiUtils.hexHtmlify("😀");       // "&#xd83d;&#xde00;"
EmojiUtils.shortCodify("😀");      // ":grinning:"（取第一个别名）
EmojiUtils.removeAllEmojis("ok😀"); // "ok"
```

### 6.2 按天备份：通过 SPI 取用

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.task.backup.BackupConfig;
import com.chua.common.support.task.backup.BackupStrategy;
import java.nio.file.Path;

BackupStrategy daily = ServiceProvider.of(BackupStrategy.class).getExtension("daily");
daily.type();            // "daily"

BackupConfig config = BackupConfig.builder()
        .backupDir(Path.of("./backup"))
        .sourceDir(Path.of("./data"))
        .retentionDays(30)
        .build();

daily.execute(config);       // BackupResult
daily.listBackups(config);   // List<Path>
daily.cleanExpired(config);  // 清理数量
```

### 6.3 自定义安全过滤器

```java
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

public class MyServerFilter implements ServerFilter {

    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        // 前置处理；需要拦截时调用 response.end(...) 并 return
        chain.doFilter(request, response);   // 放行
    }

    @Override
    public int getOrder() {
        return 50;   // 越小越先执行
    }
}
```

在自己模块的 `src/main/resources/META-INF/extensions/com.chua.common.support.network.server.filter.ServerFilter` 中登记：

```text
my-filter=com.example.MyServerFilter
```

---

## 7. 配置项

本模块**没有** Spring Boot 意义上的配置项：源码中无自动配置类、无 `@ConfigurationProperties` 属性类，引入依赖后也没有需要进 `application.yml` 的键。

全部可配置内容是第 4 节列出的**过滤器初始化参数**，在过滤器 `init(ServerFilterConfig)` 阶段经 `getInitParameter(key)` 读取，生效与否见各过滤器「生效条件」。除此之外无其它配置键。

---

## 8. SPI 扩展点

本模块是 SPI **实现方**，消费 common-starter 的两个扩展点：

| 扩展点接口（common-starter） | 本模块注册的实现 |
| --- | --- |
| `com.chua.common.support.task.backup.BackupStrategy` | `daily` → `EnhanceBackupStrategyProvider` |
| `com.chua.common.support.network.server.filter.ServerFilter` | `xss`、`path-traversal`、`request-log`、`auth`、`signature`、`crawler`、`dos`、`ip-rate-limit`、`qps-limit`、`ssrf`、`cors-header` |

声明文件位于本模块 `src/main/resources/META-INF/extensions/<接口全限定名>`，格式 `名称=实现类全限定名`。

扩展方式：实现上述接口（或自定义 `ServerFilter`），在**自己模块**的同名声明文件中登记，再用 `ServiceProvider.of(接口.class).getExtension("名称")` 装配；`getOrder()` 决定过滤器链中的先后。

---

## 9. 构建与依赖

模块位置：`utils-support-parent-starter/utils-support-extra-parent/utils-support-enhance-starter`，父模块 `utils-support-extra-parent`（聚合于 `utils-support-parent-starter`，版本 `4.0.0.51`）。

```text
utils-support-enhance-starter
└── utils-support-common-starter   4.0.0.51（唯一直接依赖；契约与工具均来自这里）
```

编译（仓库口径，JDK 25）：

```bash
mvn -o -q compile -DskipTests
```

GraalVM native-image：`META-INF/native-image/com.chua/utils-support-enhance-starter/resource-config.json` 将 `META-INF/extensions/.*` 纳入资源打包，保证 SPI 声明在原生镜像中可用。

---

## 10. 边界：这些能力不在本模块

- **HTTP 服务端与请求/响应模型**：`Server`、`ServerBuilder`、`ServerRequest`、`ServerResponse`、`ServerFilter` 接口与内置过滤器都在 `utils-support-common-starter`；本模块只提供 `ServerFilter` 的增强实现。
- **emoji 词库数据**：不内置任何 emoji 清单，`EmojiManager` 初始为空；需要完整词库请自行注册或引入数据源。
- **Spring Boot 装配**：无自动配置、无配置属性、无 starter 自动装配逻辑。
- **代理池 / 订阅解析等爬取能力**：历史文档中提及的此类条目已不在本模块源码中。
- **备份的实际落盘逻辑**：当前 `EnhanceBackupStrategyProvider` 委托 `DefaultDailyBackupStrategy` 完成，本模块未附加额外落盘行为。
- **XSS 净化结果的消费**：`XssServerFilter` 只把净化后的请求体写入请求属性 `xss.sanitizedBody`，后续如何读取该属性由使用方决定。
