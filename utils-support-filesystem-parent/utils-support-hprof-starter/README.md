# utils-support-hprof-starter

> 把 JVM 的 **HPROF 堆转储（`.hprof`）** 解析为结构化 Java 数据，并产出供人与 AI 阅读的 **JSON / Markdown / HTML 报告**，同时把整条分析链路注册为 **MCP 工具**与**文件转换 SPI** 的文件系统模块。
>
> 版本：`4.0.0.51`（随父 POM `utils-support-filesystem-parent`）｜JDK：`25`｜包名：`com.chua.hprof.support`（代码自 `4.0.0.42` 引入）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 核心 API](#3-核心-api)
  - [3.1 门面 HprofSupport](#31-门面-hprofsupport)
  - [3.2 解析：HprofParser 与 Result](#32-解析hprofparser-与-result)
  - [3.3 规则分析引擎 HprofAnalyzer](#33-规则分析引擎-hprofanalyzer)
  - [3.4 崩溃语境 CrashContext](#34-崩溃语境-crashcontext)
  - [3.5 处置计划 HprofActionPlanner](#35-处置计划-hprofactionplanner)
  - [3.6 AI 总结 HprofAiSummarizer](#36-ai-总结-hprofaisummarizer)
  - [3.7 三种报告输出（JSON / Markdown / HTML）](#37-三种报告输出json--markdown--html)
  - [3.8 MCP 工具 HprofMcpProvider](#38-mcp-工具-hprofmcpprovider)
  - [3.9 文件转换器（FileConvertSystem SPI 实现）](#39-文件转换器fileconvertsystem-spi-实现)
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
    <artifactId>utils-support-hprof-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块位于仓库路径 `utils-support-filesystem-parent/utils-support-hprof-starter`，父模块为 `utils-support-filesystem-parent`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 解析 | 基于 GridKit `hprof-heap`（Eclipse MAT 依赖的 NetBeans PerfLib HPROF 后端）把 64 位 JDK 堆转储读成类直方图、Top 保留对象、GC 根、引用链 | 不采集 / 不触发堆转储，也不 attach 运行中的 JVM |
| 报告 | 输出 JSON 文档、中文 Markdown 报告、自包含 HTML 报告（图表 + 根因 + 处置清单） | 不提供 Web 控制台或常驻服务，产出就是字符串与文件 |
| 诊断 | 内置确定性规则引擎：Top-N 集中度、集合膨胀、类加载器扇出、字符串/二进制权重、GC 根分布、非 JDK 包归属、根因判定、处置计划 | 不内置 AI 模型；AI 总结需外部注入 `ChatClient` |
| 集成 | 实现 common 的 `McpProvider`（SPI 名 `hprof`）与 `FileConvertSystem`（`hprof2json` / `hprof2markdown` / `hprof2html`）SPI | 不定义新的 SPI 接口，不持有任何业务表或业务规则 |

**数据链路**（`HprofSupport` 类注释原文）：

```text
hprof 二进制
  | Java 解析（hprof-parser）
Java 对象 / 结构化数据
  | 序列化（Jackson / 手写模板）
JSON / Markdown
  | 交给 AI
AI 给出通俗语言的结论
```

### 1.3 规模

- 主源码 **19** 个 `.java` 文件，顶层包 **11** 个：`support`、`parser`、`model`、`serializer`、`analyzer`、`differ`、`crash`、`action`、`ai`、`mcp`、`converter`。
- SPI 注册文件：`META-INF/services/com.chua.common.support.ai.mcp.McpProvider`（1 个 MCP 提供者）、`META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem`（3 个文件转换器）。
- 内置 GraalVM `native-image` 的 `reflect-config.json` / `resource-config.json`。
- 无 `src/test` 测试源码。

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| hprof → JSON 文档 | `HprofSupport.toJson(File)` | 含 meta / leak_suspects / class_histogram / analysis / class_details / ref_chains / action_plan |
| hprof → 中文 Markdown 报告 | `HprofSupport.toMarkdown(File)` | 直方图、根因判定、处置清单等完整段落 |
| hprof → 自包含 HTML 报告 | `HprofSupport.toHtml(File)` | ECharts 图表 + KPI + 发现项 + 可选 AI 摘要 |
| 解析为结构化数据 | `HprofSupport.parse(File)` / `HprofParser.parse(...)` | 直方图、Top 50 保留、GC 根、引用链、类明细 |
| 两份转储对比找增长 | `HprofSupport.diff(before, after)` | 按保留增量 / 实例增量排行的类增长榜 |
| LLM 工具调用 | `HprofMcpProvider`（SPI 名 `hprof`） | `hprof_parse_to_json` / `hprof_parse_to_html` / `hprof_top_retained` / `hprof_diagnose` |
| 统一文件转换入口 | `ConvertSupport.convert("hprof", "json"/"markdown"/"html")` | 走 common 的 `FileConvertSystem` SPI 自动匹配 |
| 根因 + 处置 + 是否 OOM | `HprofAnalyzer.analyze` + `HprofActionPlanner.plan` + `CrashContext.detect` | 纯规则、确定性、离线可用的诊断组合 |

---

## 3. 核心 API

### 3.1 门面 HprofSupport

`com.chua.hprof.support.HprofSupport`，静态工具类，一次调用完成「解析 → 序列化」。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `parse(File file)` | `HprofParser.Result` | 解析 hprof 文件为结构化数据 |
| `toJson(File file)` | `String` | JSON 文档 |
| `toMarkdown(File file)` | `String` | 中文 Markdown 报告 |
| `toHtml(File file)` | `String` | 自包含 HTML 报告（无 AI 区块） |
| `toHtml(File file, HprofAiSummarizer summarizer)` | `String` | 带 AI 总结区块的 HTML；summarizer 为 null 时省略该区块 |
| `diff(File before, File after)` | `HprofDiffer.Diff` | 两份转储按类差值比较，点名增长最快的泄漏类 |

常量：`SOURCE_HPROF="hprof"`、`TARGET_JSON="json"`、`TARGET_MARKDOWN="markdown"`、`TARGET_HTML="html"`。

### 3.2 解析：HprofParser 与 Result

`HprofParser` 包装 GridKit hprof-heap，对外输出不可变的 `Result`：

| 方法 | 说明 |
| --- | --- |
| `parse(File file)` | 从磁盘文件解析 |
| `parse(InputStream is)` / `parse(InputStream is, String fileName)` | 从流解析（字节先落临时文件再交给读取器） |
| `parseBytes(byte[] data)` | 从原始字节解析 |

`HprofParser.Result`（record，全部防御性拷贝）：

| 分量 | 含义 |
| --- | --- |
| `objects` | 按类聚合的对象行（一行 = 一个类，非单个实例） |
| `histogram` | 按类直方图（类名 / 实例数 / 浅堆 / 保留量，字节），按保留量降序 |
| `topRetained` | 保留量最高的 **50** 个类 |
| `gcRoots` | 去重后的 GC 根描述，元素形如 `种类:持有者类名` |
| `gcRootsByKind` | GC 根种类 → 根数量 |
| `classDetails` | 类名 → 类明细（保留量最大实例的字段值），仅保留量 > 0 的前 **20** 个类 |
| `refChains` | Top 类的三层引用链，最多 **10** 条 |
| `totalRetainedBytes` | 各类保留量之和（**近似值**：不同类的保留集合可能重叠，不等于全堆实际占用） |
| `totalObjectCount` | 堆内实例总数 |

底层实现 `HprofParseContext` 直接使用 `HeapFactory.createHeap(File)` / `getAllClasses()` / `getRetainedSizeByClass()` / `getGCRoots()`；`byte[]` 入参会先写入临时文件 `hprof-*.hprof`（hprof-heap 只接受 `File` 输入）。**转储较大时，调用方应把解析放到工作线程中执行。**

引用链由 `HprofRefChainWalker` 产出：第 1 层持有者实例 → 第 2 层按保留量排序的子引用 → 第 3 层子对象类名 + 保留量（每链最多 5 个子引用），例如 `ConcurrentHashMap(58MB) -> ConcurrentHashMap$Node[](58MB)`。

### 3.3 规则分析引擎 HprofAnalyzer

`HprofAnalyzer.analyze(result)` / `analyze(result, File hprofFile)` 返回 `HprofAnalysis`。引擎无状态、确定性：同输入同输出，异常直方图不抛错。内置规则：

| 规则 | 阈值 | finding key |
| --- | --- | --- |
| Top-10 保留集中度 | ≥ 50% | `high_concentration`（high） |
| 集合 + 数组权重（`java.util.*`、数组、fastutil） | > 30% 总保留 | `collection_bloat`（high） |
| 类加载器扇出 | ≥ 50 个加载器类 | `classloader_fanout`（high） |
| String / char[] / byte[] 权重 | > 10% 总保留 | `string_bloat`（medium） |
| 非 JDK 包占比（按前两段包名分组） | > 20% 总保留 | `non_jdk_holder`（high） |
| JNI global 根 | > 1000 | `jni_grefs`（medium） |
| thread object 根 | > 500 | `thread_locals`（medium） |
| 以上均未命中 | — | `top_holder` / `clean`（info） |

`HprofAnalysis` 公开字段：`totalRetainedBytes`、`totalObjectCount`、`topByRetained`、`topByInstances`、`topNRetainedBytes`、`topNRetainedRatio`、`collectionRetainedBytes`、`collectionInstances`、`classLoaderClassCount`、`classLoaderRetainedBytes`、`topClassLoaders`、`stringAndBinaryRetainedBytes`、`gcRootsByKind`、`jdkRetainedBytes`、`nonJdkRetainedBytes`、`nonJdkPackageGroups`、`jdkPackageGroups`、`findingDetails`、`conclusions`、`rootCause`、`rootCauseHeadline`、`rootCauseSections`、`rootCauseMechanisms`、`crashSignals`、`oomLikely`。关联记录：`PackageGroup(name, instances, retained)`、`RootCauseSection(label, text)`、`HprofFinding(key, severity, title, detail)`。

### 3.4 崩溃语境 CrashContext

`CrashContext.detect(File hprofFile, long totalRetained, long totalLiveCount)` 返回 `List<CrashSignal>`，按三类来源补齐「为什么崩溃」的语境：

| 来源 | 判定依据 | oomLikely |
| --- | --- | --- |
| 文件名约定 | `java_error_in_*` 前缀、`*_oom.hprof`、名称含 `outofmemory` | true |
| hs_err 伴随日志 | 同目录扫描 `hs_err*.log` / `.txt`（按修改时间新→旧），提取 `#` / `Exception` / `SIGSEGV` / `SIGBUS` / `Stack:` / `OutOfMemoryError` 签名行（截断 400 字符） | true |
| 堆水位 | 存活保留 < 2G 判「OOM 可能性低」；≥ 4G / 8G / 16G 分档判「疑似 OOM」 | 按档位 |

**降级规则**：文件名 / 日志定性为 OOM、但堆水位未达 4G 时，定性信号的 `oomLikely` 全部降级为 false，并在 evidence 追加「需结合 -Xmx 实际值复核」的存疑说明——避免把低水位常规 dump 误判成 OOM。`CrashSignal(kind, detail, evidence, oomLikely)` 的 `label()` 把 OOM 情形收敛为「疑似 OOM」，不冒充确定性结论。

### 3.5 处置计划 HprofActionPlanner

`HprofActionPlanner.plan(result, analysis)` 返回 `ActionPlan(problemSummary, items, ideaSpecific)`。每条 `ActionItem(id, title, how, expectedEffect, verify, priority)` 都是「做什么 / 怎么做 / 预期效果 / 如何验证」的可勾选步骤，优先级 1（最高）.. 3：

| 优先级 | id 前缀 | 触发条件 |
| --- | --- | --- |
| P1 | `idea-disable-webtypes` / `idea-reload-plugin` | 非 JDK 包排行头部含 `com.intellij`（IDEA 场景专属步骤） |
| P2 | `bound-collections` | 集合 + 数组保留 > 30% |
| P2 | `trim-string-cache` | 字符串 / 二进制保留 > 10% |
| P2 | `kill-threadpool` | thread object 根 > 500 |
| P3 | `release-jni` | JNI global 根 > 1000 |
| P3 | `tune-heap` | 恒有（兜底：调大 -Xmx 只是缓解，不解决泄漏） |

### 3.6 AI 总结 HprofAiSummarizer

```java
ChatClient client = ChatClient.create("openai", "sk-xxx");
String summary = HprofAiSummarizer.of(client)
        .summarize(result);
```

- `of(ChatClient)` / `of(ChatClientSetting)` 创建总结器；`model(String)` 覆盖模型名。
- 内置系统提示词：JVM 内存分析专家角色，用中文输出不超过 300 字的总结（主要内存来源、保留原因、一条最优先建议）；调用温度固定 `0.2`。
- 提示词载荷由分析结论 + Top 10 保留对象拼成 JSON；序列化失败时退化为纯文本提示词。
- 未配置 `ChatClient` 时 `summarize` 返回 `null`，HTML 渲染器随之省略 AI 区块，离线场景优雅降级。

### 3.7 三种报告输出（JSON / Markdown / HTML）

| 输出 | 实现类 | 内容 |
| --- | --- | --- |
| JSON | `HprofToJsonSerializer.serialize(result, fileName)` | 顶层键：`meta`（source_file / total_objects / total_retained_bytes / total_retained）、`leak_suspects`、`class_histogram`、`analysis`（findings / conclusions / metrics / gc_roots / non_jdk_packages / root_cause / root_cause_headline / root_cause_mechanisms / root_cause_sections / crash_context）、`class_details`、`ref_chains`、`action_plan`；另含 `isValidJson(String)` 校验 |
| Markdown | `HprofToMarkdownSerializer.serialize(result, fileName)` | 段落顺序：报告头 → 问题是什么·怎么处理 → 崩溃语境判定 → 根因判定 → 类直方图 → 非 JDK 类内存排行 → 引用链（谁持有谁）→ 泄漏嫌疑（Top 保留）→ 结论 → 处置清单（`- [ ]` 复选框） |
| HTML | `HprofToHtmlSerializer.serialize(result, fileName[, aiSummary])` | 单文件自包含：CSS / JS 内联，ECharts 经 jsDelivr CDN（`echarts@5.5.0`）加载；KPI 卡片、Top-20 保留 / 实例数柱状图、GC 根饼图、带严重级别徽标的发现项、行动卡片、引用链可视化、AI 总结区块（可选）；CDN 不可达时表格与结论仍可渲染 |

### 3.8 MCP 工具 HprofMcpProvider

`HprofMcpProvider implements McpProvider`，`@Spi("hprof")`，经 `META-INF/services` 注册，可被 `ServiceProvider.of(McpProvider.class).getExtension("hprof")` 发现。全部工具的入参均为必填的 `path`（hprof 文件路径）：

| 工具 | 返回 |
| --- | --- |
| `hprof_parse_to_json` | JSON 分析文档 |
| `hprof_parse_to_html` | 自包含 HTML 报告（配置了 `ChatClient` 时内嵌 AI 摘要区块） |
| `hprof_top_retained` | Top 保留类纯文本清单 + GC 根列表 |
| `hprof_diagnose` | 纯文本诊断卡：一句话结论、根因判定、关键证据、按优先级的解决方案（离线安全，全部来自规则引擎） |

`setChatClient(ChatClient)` / `setChatClientSetting(ChatClientSetting)` 注入可选 AI 客户端；静态方法 `HprofMcpProvider.diagnose(result, fileName)` 可在 MCP 之外直接生成诊断卡。

### 3.9 文件转换器（FileConvertSystem SPI 实现）

| SPI 名 | 实现类 | 目标格式 |
| --- | --- | --- |
| `hprof2json` | `HprofToJsonFileConvertSystem` | JSON |
| `hprof2markdown` | `HprofToMarkdownFileConvertSystem` | Markdown |
| `hprof2html` | `HprofToHtmlFileConvertSystem` | HTML（可经构造器 / `withAiSummarizer` 注入 `HprofAiSummarizer`） |

三者均实现 `isSupported("hprof", target)` 与 `convert(FileSource, FileSource, ConvertSetting)`：源支持路径 / URL / 输入流；目标支持路径（目标已存在且 `ConvertSetting` 未允许覆盖时抛 `IOException`）或输出流。注册内容（`META-INF/extensions/com.chua.common.support.file.converter.FileConvertSystem`）：

```text
hprof2html=com.chua.hprof.support.converter.HprofToHtmlFileConvertSystem
hprof2json=com.chua.hprof.support.converter.HprofToJsonFileConvertSystem
hprof2markdown=com.chua.hprof.support.converter.HprofToMarkdownFileConvertSystem
```

---

## 4. 快速开始

**依赖**：引入上方 Maven 坐标即可；传递依赖 `org.gridkit.jvmtool:hprof-heap:0.16`、`jackson-databind`、`utils-support-common-starter` 会一并带入。

**最短可运行示例**（门面 + 对比）：

```java
import com.chua.hprof.support.HprofSupport;
import com.chua.hprof.support.differ.HprofDiffer;

import java.io.File;

public class HprofDemo {
    public static void main(String[] args) throws Exception {
        File dump = new File("java_error_in_idea.hprof");

        // 1) JSON 文档（供 AI / 下游程序消费）
        String json = HprofSupport.toJson(dump);

        // 2) 中文 Markdown 报告（人可直接读）
        String markdown = HprofSupport.toMarkdown(dump);

        // 3) 自包含 HTML 报告（图表 + 根因 + 处置清单）
        String html = HprofSupport.toHtml(dump);

        // 4) 两份转储对比，定位增长最快的类
        HprofDiffer.Diff diff = HprofSupport.diff(
                new File("heapdump001.hprof"), new File("heapdump002.hprof"));
        for (HprofDiffer.ClassGrowth g : diff.topGrowths(20)) {
            System.out.println(g.className() + " 增加 " + g.retainedDelta() + " bytes");
        }
    }
}
```

**MCP 工具调用示例**（LLM agent 视角）：

```java
McpProvider provider = ServiceProvider.of(McpProvider.class).getExtension("hprof");
McpClient client = provider.create();
client.init();
McpToolResult result = client.callTool(new McpToolCall(
        "hprof_diagnose", Map.of("path", "java_error_in_idea.hprof")));
System.out.println(result.getContent());
client.close();
```

**统一文件转换入口示例**（走 common 的 `FileConvertSystem` SPI 自动匹配）：

```java
ConvertSupport.convert("hprof", "json")
        .from("java_error_in_idea.hprof")
        .to("report.json")
        .convert();
```

**带 AI 摘要的 HTML 报告**：

```java
ChatClient client = ChatClient.create("openai", "sk-xxx");
String html = HprofSupport.toHtml(dump, HprofAiSummarizer.of(client));
```

---

## 5. 配置项

**无。** 本模块不读取任何配置文件、系统属性或环境变量（源码中无 `System.getProperty` / `System.getenv` / `@Value` / `@ConfigurationProperties`），也没有 Spring Boot 自动装配配置前缀。

行为调节点全部是 API 参数与源码内常量：

| 调节点 | 说明 |
| --- | --- |
| `ConvertSetting.isOverwrite()` | 转换目标已存在时是否允许覆盖（由调用方传入） |
| `HprofAiSummarizer.model(String)` | 覆盖 AI 总结调用的模型名；温度固定 0.2 |
| `HprofMcpProvider.setChatClient(...)` | 是否为 HTML 报告注入 AI 摘要区块 |
| 解析明细上限（源码常量，非配置） | 明细类 20 个、每类实例 5 个、每实例字段 20 个、实例扫描 5000 个；引用链 10 条、每链子引用 5 个、扫描 5000 个 |

---

## 6. SPI 扩展点

本模块**不定义新的 SPI 接口**，而是作为实现方接入 common 体系的两类 SPI：

1. **`McpProvider`**（`META-INF/services` 方式）：注册 `HprofMcpProvider`，SPI 名 `hprof`，为 LLM agent 提供 4 个堆转储分析工具（见 3.8）。
2. **`FileConvertSystem`**（`META-INF/extensions` 方式）：注册 `hprof2json` / `hprof2markdown` / `hprof2html` 三个转换器（见 3.9），由 common 的 `ConvertSupport` 按「源类型 + 目标类型」自动匹配调用。

外部扩展点（注入而非注册）：

- AI 总结能力通过 `HprofAiSummarizer.of(ChatClient)` / `setChatClientSetting(ChatClientSetting)` 注入 common 的 `ChatClient`，本模块不关心具体模型协议。
- HTML 转换器 `HprofToHtmlFileConvertSystem` 可在构造时或经 `withAiSummarizer(...)` 挂接总结器。

新增能力时注意模块边界：本模块属最底层工具库，不依赖任何上层业务 starter。

---

## 7. 构建与依赖

### 7.1 编译要求

- JDK 25（根 POM `<java.version>25</java.version>`、`<release>25</release>`）。
- Lombok 以 `provided` 作用域参与编译（父模块统一注入）。
- 源码 UTF-8、无 BOM。

```bash
# 在 utils-support-parent-starter 仓库根目录
mvn -pl utils-support-filesystem-parent/utils-support-hprof-starter -am clean package
```

### 7.2 依赖清单

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | SPI 契约（`McpProvider`、`FileConvertSystem`、`ChatClient`）、集合包装 `Wraps`、`StringUtils` 等 |
| `org.gridkit.jvmtool:hprof-heap` | `0.16` | NetBeans PerfLib HPROF 后端（Eclipse MAT 同款），正确解析 64 位 JDK 堆转储（8 字节记录长度与标识符）；传递依赖 `org.gridkit.gzrand:0.2` |
| `com.fasterxml.jackson.core:jackson-databind` | 由父 POM 管理（依赖树 2.21.4） | hprof 结构化数据 → JSON |
| `org.projectlombok:lombok` | provided | 编译期注解处理 |

### 7.3 GraalVM native-image

`src/main/resources/META-INF/native-image/com.chua/utils-support-hprof-starter/` 下提供：

- `reflect-config.json`：注册 `HprofToJsonFileConvertSystem`、`HprofToMarkdownFileConvertSystem`、`HprofObject` 的全部构造器 / 方法 / 字段反射元数据；
- `resource-config.json`：资源注册。

---

## 8. 边界：这些能力不在本模块

| 能力 | 说明 |
| --- | --- |
| 生成 / 采集堆转储 | `jmap -dump`、`-XX:+HeapDumpOnOutOfMemoryError`、IDE 导出等发生在 JVM / 工具侧；本模块只解析已存在的 `.hprof` 文件 |
| attach JVM / 在线诊断 | 不连接运行中的进程、不修改堆；一切输入都是落盘的转储字节 |
| AI 模型与密钥 | AI 总结依赖外部注入的 `ChatClient`（协议实现由 common 体系其他 starter 提供），本模块不含任何模型调用实现 |
| Android 32 位转储（HAHA / Shark） | 底层为 hprof-heap，面向 64 位 JDK 堆转储；HAHA / Shark 只支持 32 位 Android 转储，不在支持范围 |
| Web 界面 / 常驻服务 | 输出为 JSON / Markdown / HTML 字符串与文件，不内嵌任何服务端或控制台 |
| 精确堆占用 | `totalRetainedBytes` 是按类求和的近似值（不同类的保留集合可能重叠），不可与其他工具的「堆已用内存」直接比较 |
| 磁盘无关的内存解析 | `byte[]` / `InputStream` 入参会先写入系统临时文件（hprof-heap 只接受 `File`）；临时空间不足时解析会失败，大转储请在磁盘充裕的机器上解析 |
| 离线图表 | HTML 报告的 ECharts 脚本来自 jsDelivr CDN；CDN 不可达时图表不渲染，但表格、发现项与结论仍正常展示 |

---

## 许可证

Apache License 2.0
