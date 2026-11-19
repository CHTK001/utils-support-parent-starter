# utils-support-deeplearning-langchain4j-starter

基于 LangChain4j 的 Agent SPI 实现：把本仓统一的 `com.chua.common.support.ai.agent.Agent` 契约落到 langchain4j 的 ReAct 循环上。

> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，`--enable-preview`）｜包名：`com.chua.deeplearning.support.langchain4j`

---

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界：这些能力不在本模块](#边界这些能力不在本模块)

---

## 它是什么 / 不是什么

| ✅ 是 | ❌ 不是 |
|---|---|
| langchain4j 版 ReAct Agent，实现本仓 `com.chua.common.support.ai.agent.Agent` SPI | Spring Boot 自动配置 starter：无自动装配类、无 `application.yml` 配置前缀 |
| MCP 工具、Skill 工具、进程内 `AgentTool` 三类工具接入 | 内置 shell 工具：`shellTool(true)` 显式抛 `UnsupportedOperationException` |
| 多轮会话历史、记忆体、上下文压缩 | 模型接入层：`ChatModel` / `StreamingChatModel` 由调用方提供 |
| 规划（plan）、失败重试、目标循环（goal）、工具结果截断 | 人工确认恢复与结构化提问恢复：`resumeStream` / `answerQuestion` 走 `Agent` 接口默认的 ERROR 事件 |
| 真实流式输出（模型实现 `StreamingChatModel` 时逐 delta 推送正文/思考） | 分块、嵌入功能的实现类（当前源码未提供，见「边界」） |

---

## 核心 API 与速查表

入口类只有一个：`com.chua.deeplearning.support.langchain4j.LangChain4jAgent`（`implements Agent`）。最常查的入口：

| 入口 | 说明 |
|---|---|
| `new LangChain4jAgent()` | 创建 Agent，全部配置走链式方法 |
| `chatModel(ChatModel)` | 绑定 langchain4j 对话模型（**必需**）；未配置时 `runStream` 直接回 ERROR 事件，同步链路抛 `IllegalStateException` |
| `run(String input)` → `AgentResponse` | 同步执行：定义解析 → 记忆检索 → 压缩 → 工具装配 →（可选规划）→ ReAct 循环（模型调用 → 工具调用 → 结果回填）→ 写回会话历史与记忆；失败按重试配置退避重试 |
| `runStream(String input)` → `Flux<AgentStreamEvent>` | 流式执行：模型实现 `StreamingChatModel` 时逐 delta 推送正文/思考，工具调用处推送 `TOOL_START` / `TOOL_RESULT`；否则退化为一次性推送 `run` 的结果 |
| `tool(AgentTool)` / `skill(String name, String description, SkillHandler handler)` | 注册进程内工具 / 本地技能 |
| `mcpManager(McpManager)` / `skillManager(SkillManager)` | 挂接全局 MCP / Skill 管理器；带 agentId 的重载实现里 agentId 被忽略，仅首次设置生效 |
| `memoryConfig(MemoryConfig)` / `compressionConfig(AgentCompressionConfig)` | 记忆体与上下文压缩 |
| `close()` | 关闭并释放资源；**关闭即中断**，正在跑的一轮在下一个循环点退出，不会留下无人消费却仍在调工具的循环 |

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-langchain4j-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 最短可运行示例

```java
import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.deeplearning.support.langchain4j.LangChain4jAgent;
import dev.langchain4j.model.chat.ChatModel;

// 1. 准备 langchain4j 的 ChatModel 实现；
//    若该实现同时实现 StreamingChatModel，runStream 即为真实逐 delta 流式。
ChatModel chatModel = /* 任意 dev.langchain4j.model.chat.ChatModel 实现 */;

// 2. 创建 Agent 并链式配置
Agent agent = new LangChain4jAgent()
        .chatModel(chatModel)
        .maxToolIterations(10)
        .mcp(true);

// 3. 同步执行：ReAct 循环（模型调用 → 工具调用 → 结果回填）
AgentResponse response = agent.run("帮我总结这段需求");
System.out.println(response.getOutput());

// 4. 流式执行（可选）
agent.runStream("再讲一遍要点")
     .doOnNext(System.out::println)
     .blockLast();

// 5. 释放资源（关闭即中断）
agent.close();
```

---

## 配置项

**无外部配置文件。** 依据：本模块 `src/main/resources` 下只有 SPI 扩展文件与 GraalVM native-image 配置，没有 `application*.yml`，源码中也没有 `@ConfigurationProperties`；全部行为通过链式 API 设置。

链式配置及源码默认值：

| 方法 | 默认值 | 说明 |
|---|---|---|
| `mode(AgentMode)` | `AgentMode.AUTO` | 运行模式（SINGLE / ROUTER / AUTO / PIPELINE / FAN_OUT / PLAN_AND_EXECUTE），传空回落 AUTO |
| `maxToolIterations(int)` | `5` | 工具调用最大迭代次数；负数由内部安全上限 `100` 兜底，避免真死循环；`0` 视为未设置，回落 `definition` 值或默认 `5` |
| `mcp(boolean)` | `true` | 是否启用 MCP 工具；关闭后退化为纯模型调用 |
| `plan(boolean)` / `startInPlanMode(boolean)` / `planMaxTask(int)` | 关 / 关 / `5` | 规划开关、首轮只规划不执行、规划最大子任务数 |
| `maxRetries(int)` / `retryBackoff(AgentRetryConfig.BackoffStrategy)` / `retryBaseDelay(long)` | `0` / `EXPONENTIAL` / `1000ms` | 最大重试次数（`-1` 表示无限）、退避策略、基础延迟；也可整体 `retryConfig(AgentRetryConfig)` |
| `toolResultEviction(int maxChars, int headChars)` | `0`（不截断） | 工具结果超长截断，保留 `headChars` 个预览字符 |
| `sessionId(String)` | — | 默认会话标识 |
| `workspace(String)` | — | 工作目录；切换会重置已建记忆管理器与压缩服务 |
| `debug(boolean)` / `printConfig(boolean)` | 关 / 关 | 系统提示词树形打印 / 架构图打印 |
| `loopGuard(AgentLoopGuard)` | 新建默认实例 | 会话护栏：死循环判定 + `interrupt()` 中断信号 |

内部常量（源码写死，不可配）：会话历史最多保留 `64` 条（仅用户/助手文本）、记忆检索返回 `5` 条、流式单次模型调用等待上限 `600` 秒。

---

## SPI 扩展点

本模块通过本仓 extensions SPI 注册到 `Agent` 契约。`src/main/resources/META-INF/extensions/com.chua.common.support.ai.agent.Agent`：

```properties
langchain4j=com.chua.deeplearning.support.langchain4j.LangChain4jAgent
```

即按键 `langchain4j` 取得的 `Agent` 实现就是本模块的 `LangChain4jAgent`。

GraalVM native-image：`META-INF/native-image/com.chua/utils-support-deeplearning-langchain4j-starter/reflect-config.json` 已注册 `LangChain4jAgent` 的全部构造器、方法与字段反射；同目录附 `resource-config.json`。

---

## 构建与依赖

| 依赖 | 版本 | 作用 |
|---|---|---|
| `com.chua:utils-support-common-starter` | 随父 POM | `Agent`、`McpManager`、`SkillManager`、`MemoryManager`、`AgentResponse` 等契约类型（`com.chua.common.support.ai.*`） |
| `dev.langchain4j:langchain4j-core` | `1.20.2`（`${langchain4j.version}`，定义于 `utils-support-deeplearning-parent`） | ReAct 循环使用的模型 / 消息 / 工具抽象 |
| `dev.langchain4j:langchain4j-embeddings-bge-small-zh-v15-q` | `1.12.2-beta22` | bge 中文嵌入依赖（当前源码树内尚无对应实现类，见「边界」） |

- 父 POM：`com.chua:utils-support-deeplearning-parent:4.0.0.51`。
- 构建：`mvn -o -q compile -DskipTests`。本模块 surefire 配置 `skipTests=false`、`skipExec=false`，若存在测试则参与构建（当前源码树暂无 `src/test`）。
- pom 注释说明：测试使用 JUnit 5（junit-jupiter `5.13.0-M3`，版本取全局管理）。

---

## 边界：这些能力不在本模块

- **分块 / 嵌入实现**：pom 描述提到「分块、嵌入」，但当前 `src/main/java` 仅有 `LangChain4jAgent` 一个实现类；分块、嵌入的实现类源码未提供（嵌入侧仅完成依赖引入）。
- **内置 shell 工具**：本实现没有任何可执行命令通道；`shellTool(false)` 天然满足，`shellTool(true)` 无法兑现，直接抛 `UnsupportedOperationException`，不接受永不生效的开关。
- **人工恢复**：`resumeStream`（人工确认恢复）与 `answerQuestion`（结构化提问恢复）未实现，走 `Agent` 接口默认的 ERROR 事件。
- **模型、MCP 服务器、技能存储、记忆存储、压缩算法本身**：分别由调用方提供的 `ChatModel` / `StreamingChatModel`，以及 `utils-support-common-starter` 的对应管理器与服务承担；本模块只负责 langchain4j 侧的编排与 SPI 落地。
