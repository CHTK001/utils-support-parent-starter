# utils-support-deeplearning-agentscope-starter

> 把项目通用 `com.chua.common.support.ai.agent.Agent` 契约接入 [AgentScope](https://github.com/agentscope-ai) Harness 的实现模块：以 `io.agentscope:agentscope-harness` 为运行时，提供多 Agent 编排、MCP 工具、技能系统、计划模式、目标循环、上下文压缩与调试轨迹等能力，模型能力则通过项目通用 `ChatClient` 注入。
>
> 版本：`4.0.0.51`（继承自父 pom）｜JDK：`25`（根 pom 统一 compiler `release=25`，含 `--enable-preview`）｜包名：`com.chua.deeplearning.support.agentscope`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 模块组成](#2-模块组成)
- [3. 核心 API 与速查表](#3-核心-api-与速查表)
- [4. 快速开始](#4-快速开始)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

| 本模块是 | 本模块不是 |
|---|---|
| 项目通用 `Agent` SPI 的 **AgentScope Harness 实现**（SPI provider 名：`agentscope`） | 不是 AgentScope 框架本体，不实现其状态机、沙箱文件系统与子代理运行时 |
| `ChatClient` / OpenAI 协议 与 AgentScope `Model` 之间的**桥接层** | 不是 `ChatClient` 的实现，也不含具体模型驱动（OpenAI/Dify 等接入由其他模块提供） |
| 支持**多 Agent 编排、MCP 工具、技能、计划模式、目标循环**的执行引擎封装 | 不含任何具体工具实现与业务提示词模板，全部由调用方注入 |
| 提供上下文压缩、调试轨迹、令牌用量收集等**横切能力** | 不含 Spring Boot 自动装配，无配置文件，纯 Java SPI 库（`starter` 仅为仓库命名约定） |

---

## 2. 模块组成

| 类 | 职责 |
|---|---|
| `AgentScopeAgent` | 主入口。`Agent` 接口的 AgentScope 实现：链式配置 → 构建 `HarnessAgent` → 同步/流式执行、中断、目标循环、资源释放 |
| `ChatClientModelAdapter` | 将项目通用 `ChatClient` 适配为 AgentScope 的 `io.agentscope.core.model.Model`：无工具走 ChatClient 流式回调；有工具走 OpenAI 流式 function calling，失败自动降级为文本流式；支持非标准 `reasoning_content` 思考增量 |
| `CompressionAwareModel` | `Model` 装饰器：纯文本轮次先经 `ContextCompressor` 压缩再下发；带工具调用的轮次原样透传（保住 `tool_call_id` 配对）；SYSTEM 角色在压缩往返中保留 |
| `CompressionChatClientModelAdapter` | 将压缩专用 `ChatClient` 适配为 `Model`，供 `AgentContextCompressionService` 在偏差矫正阶段以独立高阶模型做基线总结 |
| `ImageGenerationModel` | 将项目 `ImageClient` 适配为 `Model`，使子 Agent 具备文生图能力（图像以 base64 PNG 文本返回） |
| `AgentDebugMiddleware` | AgentScope 中间件：全量执行轨迹日志（`[AgentTrace]` 前缀）、迭代/工具调用/令牌统计、转发 `AgentDebugHook` 与 `AgentPlanHook`、注入计划修改意见、配合 `AgentLoopGuard` 做死循环判定 |
| `TokenUsageCollector` | 单次运行的令牌用量收集器（`run()` 阻塞路径拿不到流式用量事件，故按次新建、原子累加） |

---

## 3. 核心 API 与速查表

### 3.1 速查表

| 入口 | 签名 | 说明 |
|---|---|---|
| 创建实例 | `Agent.create("agentscope")` | SPI 方式获取 `AgentScopeAgent`（也支持 `Agent.create("agentscope", args)`） |
| 注入模型 | `chatClient(ChatClient)` | **必配**。另重载 `chatClient(String agentId, ChatClient)` 为指定子 Agent 单独配置 |
| 同步执行 | `run(String)` → `AgentResponse` | 阻塞执行，`response.getOutput()` 取结果文本 |
| 流式执行 | `runStream(String)` → `Flux&lt;AgentStreamEvent&gt;` | 推送正文/思考增量、工具事件与聚合用量 |
| 确认恢复 | `resumeStream(replyId, decisions)` | 对流中的 `CONFIRMATION_REQUEST` 回传决策，可附用户反馈 |
| 子 Agent | `subAgent(AgentDefinition)` | 挂载子代理（`AgentDefinition.builder()` 构建） |
| 工具管理 | `tool(AgentTool)` / `disableTool(String)` | 注册自定义工具 / 按名禁用工具 |
| 释放资源 | `close()` | 关闭底层 Harness（`Agent extends AutoCloseable`） |

### 3.2 链式配置

`AgentScopeAgent` 的全部配置均为链式方法，返回 `Agent` 以便连写：

| 分类 | 方法 |
|---|---|
| 基础 | `mode(AgentMode)`、`chatClient(ChatClient)`、`chatClient(String, ChatClient)`、`sessionId(String)`、`workspace(String)`、`definition(AgentDefinition)` / `getDefinition()` |
| MCP | `mcp(boolean)`、`mcpManager(McpManager)`、`mcpManager(String, McpManager)` |
| 技能 | `skillManager(SkillManager)`、`skillManager(String, SkillManager)`、`skill(String name, String description, SkillHandler handler)` |
| 工具 | `tool(AgentTool)`、`tools()`、`disableTool(String)`、`maxToolIterations(int)`、`toolResultEviction(int maxChars, int headChars)`、`pendingToolRecovery(boolean)`、`shellTool(boolean)` |
| 记忆与压缩 | `memoryConfig(MemoryConfig)` / `getMemoryManager()`、`compressionConfig(AgentCompressionConfig)` / `compressionConfig()` |
| 计划模式 | `plan(boolean)`、`startInPlanMode(boolean)`、`planMaxTask(int)`、`planHook(AgentPlanHook)` |
| 调试 | `debug(boolean)`、`printConfig(boolean)`、`debugHook(AgentDebugHook)`、`getSystemPrompt()` |
| 重试 | `maxRetries(int)`、`retryBackoff(AgentRetryConfig.BackoffStrategy)`、`retryBaseDelay(long)`、`retryConfig(AgentRetryConfig)` |
| 护栏 | `loopGuard(AgentLoopGuard)` / `loopGuard()`、`interrupt()`（关闭当前 Harness 以打断运行） |

### 3.3 执行与恢复

| 方法 | 说明 |
|---|---|
| `run(String input)` | 同步阻塞执行，返回 `AgentResponse`（`output` / `mode` / `selectedAgent` / `events` / `metadata` / `usage`） |
| `runAsync(String input)` | 接口默认实现，将 `run` 包装为 `CompletableFuture` |
| `runStream(String input)` | 流式执行，事件类型见 `AgentStreamEvent.Type`：`REASONING_DELTA`、`THINKING_DELTA`、`TOOL_START`、`TOOL_RESULT`、`COMPLETE`、`TRACE`、`CONFIRMATION_REQUEST`、`QUESTION_REQUEST`、`ERROR` |
| `resumeStream(String replyId, List&lt;ConfirmationDecision&gt; decisions)` | 恢复待确认的工具调用；另有附带用户反馈的重载，反馈会注入上下文促使模型重新规划 |

### 3.4 子 Agent 与目标循环

| 方法 | 说明 |
|---|---|
| `subAgent(AgentDefinition)` / `getSubAgents()` | 挂载/查看子代理；子 Agent 可注入 `ImageGenerationModel` 获得文生图能力 |
| `goal(SessionGoal)` / `attachGoal(GoalTracker)` | 绑定目标与追踪器 |
| `goalMaxRounds(int)` / `goalPauseTimeout(long)` | 目标循环轮数上限与暂停超时 |
| `hasGoal()` / `goalStatus()` | 查询目标状态（`GoalStatus`） |
| `pauseGoal()` / `resumeGoal()` / `cancelGoal(String reason)` / `runGoal()` | 目标生命周期控制 |

---

## 4. 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-agentscope-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. 最短可运行示例

前提：已有一个可用的 `ChatClient` 实例（由你选定的 `ChatClient` 实现模块提供，见[边界](#8-边界这些能力不在本模块)）。

```java
import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentResponse;

// 通过 SPI 创建："agentscope" 即本模块注册的 provider 名
Agent agent = Agent.create("agentscope")
        .chatClient(chatClient)          // 必配：注入项目通用 ChatClient
        .workspace("D:/agent-workspace") // 可选：工具/Shell 工作目录
        .maxToolIterations(10);          // 可选：工具迭代上限，默认 5

// 同步执行
AgentResponse response = agent.run("梳理 src 目录并生成模块说明");
System.out.println(response.getOutput());

// 用完释放
agent.close();
```

### 3. 流式执行

```java
agent.runStream("用 Java 写一个快速排序").subscribe(event -> {
    switch (event.getType()) {
        case REASONING_DELTA -> System.out.print(event.getDelta());   // 正文增量
        case THINKING_DELTA  -> { /* 思考过程增量 */ }
        case TOOL_START      -> System.out.printf("%n[工具] %s%n", event.getToolName());
        case COMPLETE        -> System.out.printf("%n完成，用量：%s%n", event.getUsage());
        default              -> { }
    }
});
```

### 4. 工具调用确认（human-in-the-loop）

当流中出现 `CONFIRMATION_REQUEST` 事件时，取事件中的 `replyId` 与待确认项，收集用户决策后回传：

```java
agent.resumeStream(replyId, decisions);          // 决策列表
agent.resumeStream(replyId, decisions, feedback); // 亦可附带修改意见，促使模型重新规划
```

### 5. 多 Agent 编排

```java
Agent agent = Agent.create("agentscope")
        .chatClient(chatClient)
        .subAgent(AgentDefinition.builder()
                .id("dev-agent").name("开发助手").description("代码开发专家").build())
        .subAgent(AgentDefinition.builder()
                .id("search-agent").name("搜索助手").description("联网搜索专家").build());

// run(...) 返回 AgentResponse，不是 Agent
AgentResponse response = agent.run("帮我调研并实现一个功能");
System.out.println(response.getOutput());
```

---

## 5. 配置项

本模块**无 Spring 配置键、无配置文件**，全部行为通过链式 API 设置。链式方法与源码内默认值：

| 链式方法 | 默认值 | 说明 |
|---|---|---|
| `maxToolIterations(int)` | `5` | 单次运行工具调用迭代上限 |
| `toolResultEviction(maxChars, headChars)` | `8000` / `1500` | 工具结果超长时的截断保留策略 |
| `shellTool(boolean)` | `true` | 是否注入 Shell 工具（依赖 agentscope-harness 2.0.4 的 shell 工作目录能力） |
| `mcp(boolean)` | `true` | 是否启用 MCP 工具体系 |
| `retryBaseDelay(long)` | `1000`（毫秒） | 重试基础退避延迟，配合 `maxRetries` / `retryBackoff` / `retryConfig` |
| `goalMaxRounds(int)` | `20` | 目标循环最大轮数 |
| `plan(boolean)` / `startInPlanMode(boolean)` | 关闭 | 计划模式开关 / 是否以计划模式启动 |
| `debug(boolean)` / `printConfig(boolean)` | 关闭 | 调试轨迹与构建配置打印 |
| `workspace(String)` / `sessionId(String)` | 无 | 工作目录与会话标识 |

`ChatClientModelAdapter`（工具路径）的内部行为参数：默认 baseUrl `https://api.openai.com/v1`、请求超时 `120s`、`maxTokens` 默认 `4096`；`thinkingBudget > 0` 时映射为 effort=`medium`。

系统属性读取：模块内只读 `java.io.tmpdir`，用于压缩工作目录兜底——`AgentScopeAgent` 在压缩启用而记忆配置未提供 workspace 时，以 `java.io.tmpdir` 拼接 `agentscope-compression` 解析出工作目录传给 `CompressionAwareModel`；`CompressionAwareModel` 构造器对未传入的 workspace 亦做同一兜底。

---

## 6. SPI 扩展点

### 本模块注册的 SPI

资源文件 `META-INF/extensions/com.chua.common.support.ai.agent.Agent`：

```properties
agentscope=com.chua.deeplearning.support.agentscope.AgentScopeAgent
```

即通过 `Agent.create("agentscope")` 获取本模块实现。AgentScope 内部的模型注册 id 形如 `agentscope:<prefix>:<hash>`。

### 本模块消费的契约（均来自 `utils-support-common-starter`）

| 契约 | 用途 | 是否必需 |
|---|---|---|
| `ChatClient` | 模型对话能力来源，经 `ChatClientModelAdapter` 桥接 | **必需** |
| `McpManager` | MCP 工具接入 | 可选 |
| `SkillManager` / `SkillHandler` | 技能系统 | 可选 |
| `AgentTool` | 自定义工具注册 | 可选 |
| `ImageClient` | 子 Agent 文生图（经 `ImageGenerationModel`） | 可选 |
| `ContextCompressor` / `AgentCompressionConfig` | 上下文压缩 | 可选 |
| `AgentDebugHook` / `AgentPlanHook` | 调试与计划回调 | 可选 |
| `AgentLoopGuard` | 死循环护栏（连续重复的工具名+入参达到阈值即触发 `interrupt()`） | 可选 |
| `MemoryConfig` / `MemoryManager` | 会话记忆 | 可选 |
| `AgentRetryConfig` | 重试策略 | 可选 |
| `SessionGoal` / `GoalTracker` | 目标循环 | 可选 |

---

## 7. 构建与依赖

坐标：`com.chua:utils-support-deeplearning-agentscope-starter:4.0.0.51`，隶属于 `utils-support-deeplearning-parent`。

| 依赖 | 版本 | 说明 |
|---|---|---|
| `io.agentscope:agentscope-harness` | `2.0.4` | AgentScope 运行时。选 2.0.4 是因其提供 shell 工作目录能力（`LocalFilesystemWithShell` 的 `shellCwd` / `resolveExecuteCwd`）与 `ShellExecuteTool` 注入点 |
| `com.openai:openai-java` / `openai-java-core` / `openai-java-client-okhttp` | 根 pom 托管 `4.76.0` | 工具路径的 OpenAI 流式 function calling；顶层 `openai-java` 为聚合 stub，实际类在 core / client-okhttp |
| `com.chua:utils-support-common-starter` | 同仓 | `Agent`/`ChatClient` 等契约与工具 |

另：本模块 `dependencyManagement` 将 `com.squareup.okhttp3:okhttp` 锁定为 `4.12.0`（agentscope 会同时传递 okhttp 4.x 与 5.x，需统一）。

```bash
# 在仓库根编译（离线）
mvn -o -q compile -DskipTests

# 仅编译本模块（含上游依赖）
mvn -o -q -pl utils-support-deeplearning-parent/utils-support-deeplearning-agentscope-starter -am compile -DskipTests
```

要求 JDK 25 并开启 preview（根 pom 统一配置 compiler `release=25` + `--enable-preview`）。

---

## 8. 边界：这些能力不在本模块

| 能力 | 所在位置 |
|---|---|
| `Agent` / `ChatClient` / `McpManager` / `SkillManager` / `ImageClient` / `ContextCompressor` 等契约与默认实现 | `utils-support-common-starter` |
| 具体模型驱动（OpenAI、Dify、通义等 `ChatClient` 实现）与渠道管理 | 由你引入的 `ChatClient` 实现模块及上层业务 starter 提供 |
| AgentScope 运行时本体（状态持久化、沙箱文件系统、子代理调度、事件总线） | `io.agentscope:agentscope-harness:2.0.4` 及其传递依赖 |
| 其他 Agent 运行时实现（ONNX、sherpa-onnx、LangChain4j、Llama.cpp 等） | `utils-support-deeplearning-parent` 下的兄弟模块 |
| Spring Boot 自动装配 | 无——本模块是纯 Java SPI 库，引入即用，无配置文件 |
