# utils-support-datasearch-starter

数据源适配与检索实现集合：视频/音乐搜索、天气与地理信息等在线查询，AI 工具的本地用量和会话解析，技能/插件/MCP 目录，以及模型价格与能力目录。它不负责宿主的数据落库、账号权限、任务调度或模型推理。

## 安装与依赖

JDK 25：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datasearch-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

当前依赖包含 common、datasource、Playwright、Jsoup、Reactor 与 zstd-jni，另有可选 Spring JDBC 适配。核心调用通过公共门面或 `ServiceProvider` 查找 SPI；不是所有查询都需要浏览器。POM 的历史描述提到 WebDAV/JDBC/同步输入，实际文件存储、数据库和同步执行能力应分别查看对应模块。

## 视频与音乐

```java
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.VideoSearchService;
import com.chua.common.support.datasearch.music.spi.MusicSourceProvider;
import com.chua.common.support.spi.ServiceProvider;

var search = new VideoSearch("纪录片");
search.setPage(1);
search.setPageSize(10);
var videos = new VideoSearchService().search(search);
System.out.println(videos.getData());

var music = ServiceProvider.of(MusicSourceProvider.class).getExtension("netease");
if (music == null) {
    throw new IllegalStateException("音乐源未安装");
}
System.out.println(music.search("民乐", 1, 10));
```

`VideoSearchService` 依次查询已登记且未被 `VideoProviderRegistry` 封禁的 `ResourceProvider`，单源异常跳过。结果合并各源当前页，total 为各源 total 之和；没有全局去重或再次分页，返回行数可能超过 pageSize。需要限定某个源时直接选对应 `ResourceProvider`。

视频扩展还包含字幕查询、下载链接和 `VipParseService`；解析依赖源实际返回的地址，不保证解除平台权限限制。音乐注册源为 `tx` 与 `netease`，接口包括歌曲/歌单搜索、分类、歌单详情与曲目详情；数据和播放地址受上游登录、地域、版权与接口变化影响。

## AI 工具的本地用量

```java
import com.chua.common.support.datasearch.usage.spi.impl.ScnetUsageParser;
import java.time.Duration;

// 不传参数时读取当前用户主目录；这里仅消费前 100 条，避免全量收集。
var parser = new ScnetUsageParser();
var sample = parser.streamAll().take(100).collectList().block(Duration.ofSeconds(30));
System.out.println(sample);
```

`UsageParser.streamAll()` 返回 `Flux<AiUsage>`。实现包括 OpenCode、Claude Code、Codex、Gemini CLI、Qoder、SCNet 等本地格式；`ConversationParser.streamMessages()` 平行提供会话消息流，二者处理的维度不同。用量缺失不表示某工具未安装，可能是路径、权限或记录格式不匹配。

`BaseUsageParser` 子类的字符串构造参数支持多个用户根目录，以逗号、分号或换行分隔，支持 `~` 和同级用户目录的 `~账号` 简写；无配置回退 `user.home`。多个目录串行处理，单目录异常记录告警并跳过，因此空流不能证明全部输入读取成功。SCNet 会读取 `.scnet/clusters` 和 `.scnet/chats` 两处记录；其它解析器有自己的目录规则。

统一 Token 口径：输入量包含缓存命中量，命中量不超过输入量；输出量与推理量分列，total 为输入量加可见输出量。`UsageFieldCompleter`、`PricingSyncer` 和 `UsagePricingReconciler` 处理字段补齐、价格同步及重新计价；解析不等于已经落库，宿主应订阅流、处理失败并保存结果。对大目录不要直接无界 `collectList()`。

## 技能、插件与 MCP 编辑器配置

- `SkillOnlineProvider` 查询技能市场，包含 SkillsMP、ClawHub、SkillHub、GitHub 等实现；`SkillOfflineProvider` 扫描编辑器本地技能。市场搜索结果与本地安装结果分别读取。
- `PluginOnlineProvider` / `PluginOfflineProvider` 处理在线插件目录与本地扩展，包括 VS Code Marketplace、Open VSX、GitHub 和各编辑器目录。
- `AgentEditorProvider` 读取 MCP 配置、列出已安装编辑器、生成连接配置，并可显式安装/卸载配置；`autoBind()` 绑定音乐和视频 SPI，不会自动启动外部 MCP 服务。
- `ModelsDevIndicator` 与 `ModelMetricsProvider` 的 models.dev、OpenRouter、Vercel、Artificial Analysis 适配提供模型目录/价格信息。目录快照不证明某模型在用户渠道中可用，也不代替真实推理或账单。

只读检查编辑器配置的例子：

```java
import com.chua.common.support.datasearch.agent.AgentEditorProvider;

var editors = new AgentEditorProvider();
System.out.println(editors.listInstalled());
System.out.println(editors.readAllMcpConfigs());
```

`installTo/installToAll/uninstallFrom` 会改写本地编辑器配置，使用前应确认目标目录与合并行为。读取出的配置也可能含令牌，不应直接写入共享日志。外部 MCP 安装器还可能依赖 Node、Python 或包管理器，不能视为引入依赖就安装完成。

## 其它查询族与运行条件

| 领域 | 主要 SPI/入口 | 配置与边界 |
| --- | --- | --- |
| 天气、定位与地理 | `WeatherProvider`、`LocationProvider`、`GeocodeProvider`、`RegionProvider` | Open-Meteo、wttr.in、IP/地理信息源；按接口传城市、IP 或坐标 |
| 生活与语言数据 | `HolidayProvider`、`HanziProvider`、`IdiomProvider`、`PoetryProvider`、`PhoneLocationProvider`、`HoroscopeProvider` | 数据来自实际在线源，格式/可用性可能变化 |
| 汇率、台风、快递 | `ExchangeRateProvider`、`TyphoonProvider`、`ExpressProvider` | 接口存在与具体实现可用性分别确认；快递不能仅凭契约声称已有可用平台 |
| 临时邮箱与短信 | `EmailProvider`、`SmsCodeProvider` | 邮箱/号码与消息来自第三方服务；接码号的短信读取使用 Playwright 且受站点挑战限制 |
| 表情、代理与软件 | `EmojiProvider`、`ProxyFetcher`、`SoftwareProvider` | 在线检索、代理列表、包管理器软件信息；列表存在不证明代理可达或软件已安装 |

各 SPI 注册名及实现见本模块 `src/main/resources/META-INF/services` 和 `META-INF/extensions`。在线接口需要真实网络与可能的 API Key/Cookie；本地解析需要相应文件及读取权限。Playwright 路径还需要浏览器和可用会话。当前模块没有统一的“零配置即可全部使用”保证。

`DataSearchServerFilter` 是可选的服务器过滤链适配，加入宿主链路后才提供相应 HTTP 入口，不会在依赖引入时自动启动服务。

## 离线回归入口

POM 的 `verify-fixtures` profile 绑定了四项离线自检：

```bash
cd utils-support-datatask-parent/utils-support-datasearch-starter
mvn -o verify -Pverify-fixtures
```

| execution | 校验范围 |
| --- | --- |
| `verify-video-spi` | 视频与字幕 SPI 登记、名称反查、封禁名单行为 |
| `verify-music-spi` | `tx` / `netease` 源登记、名称与编码 |
| `verify-scnet-usage` | SCNet 双目录夹具、compaction、缓存/推理拆分、耗时、去重与全零剔除 |
| `verify-multihome-usage` | 多根目录解析、去重、目录异常隔离与聚合行为 |

单项可用 `mvn -o test-compile exec:java@run-video-spi-test`，其余 execution 为 `run-music-spi-test`、`run-scnet-usage-test`、`run-multihome-usage-test`。profile 默认关闭；自检通过只证明对应离线夹具，不证明在线源、浏览器挑战或本机所有工具格式都可用。本次文档核对不启动浏览器，也不调用这些在线源。

返回 [数据任务目录](../README.md) / [完整目录](../../README.md)。
