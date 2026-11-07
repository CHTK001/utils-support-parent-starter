# Agent 运行时能力矩阵（A–E）

> 适用版本：`utils-support-parent-starter` 4.0.0.50
> 覆盖实现：`opencode-server`（默认 SPI）、`agentscope`、`langchain4j`
> 口径：只描述 **Agent 运行时能力**。多端形态、通道、UI、主题、通知、Voice、Git 界面、
> Scheduled Tasks、Magic Prompts、Usage 看板不在本文范围。
>
> 每一项按「**真实现 / 显式失败 / 不适用**」三态标注：
>
> | 标记 | 含义 |
> |---|---|
> | ✅ | 真实现，配置项真正下发到运行时 |
> | ◐ | 有限实现，边界写入备注（不影响主链路） |
> | ⛔ | 显式失败（`UnsupportedOperationException` 或 ERROR 事件），并给出替代路径 |
> | ➖ | 该实现不需要（能力由外部承担） |
>
> 原则：**不允许「存值 + 忽略 + WARN」**。无法兑现时必须抛异常或回 ERROR 事件。

---

## A. 执行编排

| 能力（接口方法） | opencode-server | agentscope | langchain4j |
|---|---|---|---|
| `mode(AgentMode)` | ◐ `SINGLE`/`AUTO`/`ROUTER` 落到 opencode 原生「主 agent + `task` 委派子 agent」语义；`PLAN_AND_EXECUTE` ⇒ `plan(true)`；`PIPELINE`/`FAN_OUT` ⛔（无顺序/并行编排原语） | ✅ `mode.name()` 下发 `Harness`，`ROUTER` 追加 ROUTER 路由头 | ✅ 记录并以响应 `mode` 字段回传；子 Agent 走提示词路由（接口契约即提示词路由） |
| `subAgent(AgentDefinition)` / `getSubAgents()` | ✅ 写 `agent.<name>.mode=subagent`（递归 `definition.agents`）；`leader` 子 Agent ⛔ | ✅ 生成 `SubagentDeclaration` 交给 Harness 实际委派 | ✅ 注入 system prompt 路由表（接口契约口径），由主模型分派 |
| `getSystemPrompt()` | ✅ 沿用接口默认（`definition` 自动生成 prompt），该值即写入 `agent.prompt` 的文本 | ✅ 运行期 `subAgent(...)` 追加、`definition` 未声明的子 Agent 并入路由表 | ✅ 同左（`undeclaredSubAgents()` + 路由表拼接） |
| `goal(SessionGoal)` 及 `attachGoal` / `goalMaxRounds` / `goalPauseTimeout` / `hasGoal` / `goalStatus` / `pauseGoal` / `resumeGoal` / `cancelGoal` / `runGoal` | ✅ 共用 `AgentGoalSupport` 状态机 | ✅ 同左 | ✅ 同左 |
| `plan(boolean)` / `startInPlanMode(boolean)` | ✅ 写 `permission.plan_enter` / `permission.plan_exit=allow` | ✅ `enterPlanMode(ctx)`（可在首轮前进入） | ✅ 真两段式：先规划，规划后只返回计划并标 `planOnly` |
| `planMaxTask(int)` | ✅ 仅接受 `0`；`>0` ⛔（opencode 无子任务上限配置） | ✅ 下发中间件，限制计划任务数 | ✅ 限制规划子任务数 |
| `planHook(AgentPlanHook)` | ⛔ Java 回调无法跨进程；替代：`plan(true)` / `startInPlanMode(true)` | ✅ 规划事件回调 | ✅ `PLAN_ENTER` / `PLAN_WRITE` / `PLAN_EXIT` |
| `definition(AgentDefinition)` | ✅ 可用；跨进程不可桥接项在 `validateDefinition` 中逐项 ⛔（见下表） | ✅ | ✅ |
| `run(String)` / `runAsync(String)` | ✅ | ✅ | ✅ |

### A.1 opencode 跨进程不可桥接项（`validateDefinition` 逐项显式失败）

| 传入项 | 结果 | 替代路径 |
|---|---|---|
| `McpManager` | ⛔ | `mcpServer(name, spec)` 写 `opencode.json` 的 `mcp` 段 |
| `SkillManager` | ⛔ | `skillPath(..)` 指向 `.opencode/skill/` 下的 `SKILL.md` |
| `MemoryConfig` | ⛔ | opencode 自管会话记忆；项目级长期记忆写 `AGENTS.md` |
| `PlanHook` | ⛔ | `plan(true)` / `startInPlanMode(true)` |
| `DebugHook` | ⛔ | `debug(true)` 输出适配层日志 |
| `planMaxTask > 0` | ⛔ | 无对应项，保持 `0` |
| `retryConfig.maxRetries != 0` | ⛔ | opencode 侧 `provider` 重试配置，或外部 `RetryFlow` 包裹 |
| `leader=true`（子 Agent） | ⛔ | 主 Agent 用 `mode(ROUTER/AUTO)` 委派 |
| `compressionConfig.compressionChatClient` | ⛔ | 只设 `compressionConfig.enabled`（`compaction.auto`） |

---

## B. 循环可靠性与恢复

| 能力（接口方法） | opencode-server | agentscope | langchain4j |
|---|---|---|---|
| `loopGuard(AgentLoopGuard)` / `loopGuard()` | ✅ 工具事件前 `recordToolCall`，命中即中断 | ✅ 中间件按 `callId` 拼完入参再判定重复 | ✅ 每轮工具调用前判定 |
| `maxToolIterations(int)` | ✅ 写 `agent.<name>.steps` | ✅ 主/子 Agent 分别生效 | ✅ 主/子 Agent 分别生效 |
| `maxRetries` / `retryBackoff` / `retryBaseDelay` / `retryConfig` | ◐ 只接受「不启用重试」：`maxRetries=0`、`retryBackoff(null)`、`retryBaseDelay<=0`、`retryConfig(null 或 maxRetries==0)`；任何真启用 ⛔ | ✅ 真重试循环 + 退避计算 | ✅ 真重试循环 + 退避计算 |
| `toolResultEviction(int,int)` | ◐ 在事件层截断 `TOOL_START` / `TOOL_RESULT` 载荷（opencode 进程内上下文由它自管） | ✅ `ToolResultEvictionConfig(maxResultChars, previewChars)` 下发，完整内容落盘 | ✅ 工具结果写回上下文前截断 |
| `pendingToolRecovery(boolean)` | ✅ 关闭后 `resumeStream` / `answerQuestion` 直接 ERROR 并逐出待批缓存 | ✅ 同左，`builder.enablePendingToolRecovery` | ⛔ 沿用接口默认（无暂停点） |
| `resumeStream(replyId, decisions[, feedback])` | ✅ 回 `POST /api/session/{id}/permission/{requestID}/reply`（`once`/`reject`） | ✅ 取回 `PENDING_BLOCKS` 恢复暂停工具 | ⛔ 接口默认 ERROR 事件（无人工确认暂停点） |
| `answerQuestion(replyId, answers)` | ✅ 回 `POST /api/session/{id}/question/{requestID}/reply`，按 `questionId` 还原问答顺序 | ⛔ 接口默认 ERROR 事件（无结构化提问通道） | ⛔ 接口默认 ERROR 事件 |
| `memoryConfig(MemoryConfig)` / `getMemoryManager()` | `memoryConfig(null)` ✅，非空 ⛔；`getMemoryManager()` 显式返回 `null` | ✅ 懒建 `MemoryManager`：检索命中即注入系统提示词「相关长期记忆」，每轮结束写回；落盘目录随 `workspace` 走 `<ws>/.agent/memory` | ✅ 完整记忆管理器（同口径：检索注入 + 每轮写回） |
| `compressionConfig(AgentCompressionConfig)` | ✅ 写 `compaction.auto`（带 ChatClient 的压缩 ⛔） | ✅ 支持 `compressionChatClient` 两阶段压缩 | ✅ 支持 `compressionChatClient` 与偏差矫正 |

---

## C. 工具面

| 能力（接口方法） | opencode-server | agentscope | langchain4j |
|---|---|---|---|
| `tool(AgentTool)` / `tools(List)` / `tools()` | ⛔ 进程内执行器无法跨进程回调；`tools()` 返回空列表 | ✅ 注册到 `Toolkit`，`callAsync` 真执行 | ✅ 进工具循环，`ToolExecutor` 真执行 |
| `disableTool(String)` | ✅ 写 `agent.<name>.tools.<tool>=false`（实测 `debug agent` 为 `false`） | ✅ 注册前按名称裁剪（Skill → 进程内工具 → MCP 顺序） | ✅ 下发前按名称裁剪 |
| `skill(name, desc, handler)` | ⛔ handler 无法跨进程；替代 `.opencode/skill/<name>/SKILL.md` + `skillPath(..)` | ✅ 包成工具注册 | ✅ 包成工具注册 |
| `skillManager(...)` | ⛔ | ✅ | ✅ |
| `mcp(boolean)` | ✅ 开关 `opencode.json` 的 MCP 段 | ✅ 开关 MCP 工具注册 | ✅ 开关 MCP 工具注册 |
| `mcpManager(...)` | ⛔；替代 `mcpServer(name, spec)` | ✅ 包成 `McpAgentTool` | ✅ 包成 `ToolExecutor` |
| `shellTool(boolean)` | ✅ 写顶层 `permission.bash` | ✅ `disableShellTool()` | `false` ✅ / `true` ⛔（无内置 shell 通道） |

---

## D. 流式可观测

事件类型见 `AgentStreamEvent.Type`：`REASONING_DELTA`、`THINKING_DELTA`、`TOOL_START`、
`TOOL_RESULT`、`COMPLETE`、`TRACE`、`CONFIRMATION_REQUEST`、`QUESTION_REQUEST`、`ERROR`。

| 事件 / 能力 | opencode-server | agentscope | langchain4j |
|---|---|---|---|
| `runStream(String)` | ✅ SSE 真流式 | ✅ Harness 事件流 | ✅ `StreamingChatModel` 真增量；非流式模型回退一次性下发 + `COMPLETE` |
| `REASONING_DELTA` | ✅ 正文增量 | ✅ | ✅ `onPartialResponse` |
| `THINKING_DELTA` | ✅ 推理增量 | ✅ | ✅ `onPartialThinking` |
| `TOOL_START` / `TOOL_RESULT`（含 `task` 子任务） | ✅ | ✅ | ✅ |
| `TRACE`（`AgentTrace`） | ⛔ opencode 进程无 span 概念；替代 `debug(true)` 日志 + 工具事件 | ✅ 模型/工具 span | ✅ 模型/工具 span 与边 |
| `CONFIRMATION_REQUEST` | ✅ `permission.v2.asked`，带回 `replyId` | ✅ | ⛔ 无暂停点，走 ERROR |
| `QUESTION_REQUEST` | ✅ `question.v2.asked`，带回 `replyId` + `questionId` | ⛔ | ⛔ |
| `COMPLETE` / `ERROR` | ✅ | ✅ | ✅ |
| 用量 `AiUsage` | ✅ `COMPLETE` 事件携带 | ✅ | ✅ |

---

## E. 模型与运行环境

| 能力（接口方法） | opencode-server | agentscope | langchain4j |
|---|---|---|---|
| `chatClient(ChatClient)` | ✅ 取 provider/model 写 `model` | ✅ 主模型客户端 | ✅ 主模型（`ChatModel`/`ChatClient`） |
| `chatClient(agentId, ChatClient)` | ✅ 写 `agent.<name>.model` | ✅ 子 Agent 专属模型 | ✅ 子 Agent 专属模型 |
| `workspace(String)` | ✅ 进程工作目录（`cwd`） | ✅ `LocalFilesystemSpec.project(ws)`（必须与 workspace 同值） | ✅ 记忆/压缩基线落盘根 |
| `sessionId(String)` | ✅ 复用/新建 opencode 会话 | ✅ harness 会话目录隔离 | ✅ 跨轮会话键 |
| `printConfig(boolean)` | ✅ 适配层架构日志 | ◐ 仅日志提示「请使用 `debug(true)`」 | ✅ 架构概览日志 |
| `debug(boolean)` | ✅ 适配层详细日志 | ✅ 全量轨迹中间件 | ✅ 详细日志 |
| `debugHook(AgentDebugHook)` | ⛔ | ✅ `PRE/POST_REASONING`、`PRE/POST_ACTING`、`ERROR` | ✅ 同左 |
| `interrupt()` / `isInterrupted()` | ✅ 中断 + 关闭当前轮 | ✅ 关闭 `Harness` 打断阻塞调用 | ✅ 中断标记 + 循环退出 |
| `close()` | ✅ 关进程/会话 | ✅ 关 `Harness` | ✅ 关记忆管理器/压缩服务 |
| `getDefinition()` / `getSubAgents()` | ✅ | ✅ | ✅ |

---

## F. 选型速查

| 诉求 | 推荐 |
|---|---|
| 要 opencode 生态（原生 plan、permission、question、MCP、SKILL.md） | `opencode-server` |
| 要进程内工具/技能/MCP、人工确认恢复、真实 trace、重试 | `agentscope` |
| 要进程内工具/技能、两段式规划、目标循环，且不依赖 agentscope | `langchain4j` |
| 需要结构化提问（`QUESTION_REQUEST`） | 仅 `opencode-server` |
| 需要 `PIPELINE` / `FAN_OUT` 多 Agent 编排 | `opencode-server` 不支持；`agentscope`/`langchain4j` 走各自 `mode` 语义 |

---

## G. 验收命令与实测口径

构建环境：Corretto JDK 25.0.3、Maven 3.9.9（`D:\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd`）、
本地仓库 `D:\maven-repo`、离线 `-o`、版本 `4.0.0.50`。

> 注意（都是踩过的坑）：
> - `-pl` 必须用**相对路径**，绝对路径会报 `Could not find the selected project in the reactor`。
> - 含点号的 `-D` 参数在 PowerShell 里会被拆词，**必须整体加引号**，如 `'-Dtest=A,B'`；
>   而 `-pl` 的逗号列表要整体包成一个字符串，否则第二个路径会被当成独立参数。
> - 跨模块跑指定测试必须加 `'-Dsurefire.failIfNoSpecifiedTests=false'`。
> - 根 pom 默认 `skipTests=true`，跑测试必须显式 `'-DskipTests=false'`。
> - 单模块 `-pl` 会用到本地仓库里落后的 common jar，测试必须把 common 放进同一 reactor。
> - 本机多会话共用，内存紧张（实测空闲常在 1.8GB～6GB 之间）。`MAVEN_OPTS` 给 **512m～900m** 就够；
>   给到 1280m 反而会 `os::commit_memory failed ... (DOS error/errno=1455)`（页面文件太小）。
>   单测还要用 `'-DargLine=-Xmx640m'`（或 512m）压住 surefire fork 的 JVM，
>   两处都调对才不会出现 `insufficient memory ... Chunk::new` 崩溃。

### G.1 四模块编译

```powershell
$env:MAVEN_OPTS='-Xmx900m -XX:MaxMetaspaceSize=320m -XX:ReservedCodeCacheSize=96m'
D:\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd -o -pl `
  "utils-support-core-parent/utils-support-common-starter,utils-support-core-parent/utils-support-deeplearning-starter,utils-support-deeplearning-parent/utils-support-deeplearning-agentscope-starter,utils-support-deeplearning-parent/utils-support-deeplearning-langchain4j-starter" `
  clean compile
```

期望结尾：`BUILD SUCCESS`（本轮实测 `EXIT=0`，四模块 `compile` 全绿）。

### G.2 opencode-server 测试（11 例）

```powershell
$env:MAVEN_OPTS='-Xmx768m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m'
D:\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd -o -pl `
  "utils-support-core-parent/utils-support-common-starter,utils-support-core-parent/utils-support-deeplearning-starter" `
  '-DskipTests=false' '-Dtest=OpencodeRetryWiringTest,OpencodeToolWiringTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

实测（`D:\ch\agent-verify-opencode.log`）：`OpencodeRetryWiringTest 7` + `OpencodeToolWiringTest 4` = **11**，
`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

### G.3 agentscope 测试（54 例）

```powershell
$env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=320m -XX:ReservedCodeCacheSize=96m'
D:\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd -o -pl `
  "utils-support-core-parent/utils-support-common-starter,utils-support-deeplearning-parent/utils-support-deeplearning-agentscope-starter" `
  '-DskipTests=false' '-DargLine=-Xmx640m' `
  '-Dtest=AgentScopeGoalWiringTest,AgentScopeToolWiringTest,AgentScopeMemoryWiringTest,AgentSubAgentRoutingTest,CompressionAwareModelTest,PendingBlocksCacheTest,TokenUsageCollectorTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

实测（`D:\ch\as-full-test.log`）：`AgentScopeGoalWiringTest 6` + `AgentScopeToolWiringTest 8`
+ `AgentScopeMemoryWiringTest 4` + `AgentSubAgentRoutingTest 22` + `CompressionAwareModelTest 6`
+ `PendingBlocksCacheTest 4` + `TokenUsageCollectorTest 4` = **54**，`Failures: 0, Errors: 0`，
`BUILD SUCCESS`。

### G.4 langchain4j 测试（13 例）

```powershell
$env:MAVEN_OPTS='-Xmx900m -XX:MaxMetaspaceSize=320m -XX:ReservedCodeCacheSize=96m'
D:\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd -o -pl `
  "utils-support-core-parent/utils-support-common-starter,utils-support-deeplearning-parent/utils-support-deeplearning-langchain4j-starter" `
  '-DskipTests=false' '-DargLine=-Xmx512m' `
  '-Dtest=LangChain4jGoalWiringTest,LangChain4jToolWiringTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

实测（`D:\ch\agent-verify-langchain4j.log`）：`LangChain4jGoalWiringTest 8` + `LangChain4jToolWiringTest 5` = **13**，
`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

三组合计 **78 例全绿**。日志在 PowerShell 里是 UTF-16LE，按
`[System.Text.Encoding]::Unicode` 解码后再 grep。

### G.5 opencode 真机抽查（可选，v1.18.7）

> 与适配器版本一致的是 `C:\Users\Administrator\AppData\Roaming\npm\node_modules\opencode-ai\bin\opencode.exe`；
> OpenChamber 自带的是 v2.2.5，**不要混用**。

必须先把 `OPENCODE_CONFIG` / `OPENCODE_CONFIG_CONTENT` 置空，并把 `XDG_*` 四个变量指到 D: 盘，
再用 `cmd /c "... > file"` 落盘。抽查点：

- `debug agent <name>` 输出中 `steps` / `mode` / `tools` / `permission` 与设置一致；
- `tools: {bash:false, webfetch:false, task:false}` 在 `disableTool` 后为 `false`；
- `opencode.json` 的 `agent` / `mcp` / `compaction` 段与 `AGENTS.md` 标记块 `<!-- BEGIN chua-agent-config -->` 幂等。

---

## H. 本轮涉及的源码与测试

| 模块 | 文件 |
|---|---|
| common | `ai/agent/Agent.java`、`AgentTool.java`、`AgentQuestion.java`、`AgentQuestionAnswer.java`、`AgentStreamEvent.java`、`goal/AgentGoalSupport.java` |
| opencode-server | `agent/opencode/OpencodeServerAgent.java`、`OpencodeProjectConfig.java` |
| agentscope | `agentscope/AgentScopeAgent.java` |
| langchain4j | `langchain4j/LangChain4jAgent.java` |
| 测试 | `OpencodeRetryWiringTest`、`OpencodeToolWiringTest`、`AgentScopeGoalWiringTest`、`AgentScopeToolWiringTest`、`AgentScopeMemoryWiringTest`、`AgentSubAgentRoutingTest`、`CompressionAwareModelTest`、`PendingBlocksCacheTest`、`TokenUsageCollectorTest`、`LangChain4jGoalWiringTest`、`LangChain4jToolWiringTest` |
