# AGENTS.md — utils-support-parent-starter

> **模块边界与依赖铁律：[`../规则.md`](../规则.md)（`D:\ch\project\规则.md`），动手前必须先读并遵守。违反 R1/R2 = S0 严重 bug。**
> 全局规则：`C:\Users\Administrator\.config\opencode\AGENTS.md`；源码编辑红线：`C:\Users\Administrator\.agents\GLOBAL-SOURCE-EDITING-RULES.md`。

## 本仓库定位

**最底层工具库**。依赖方向：`spring-support-api-*` → `spring-support-parent` → `utils-*`。本仓库**不依赖任何上层业务 starter**。

## 三条铁律（摘要，全文以 `规则.md` 为准）

1. **R1 禁止业务穿插**：本仓库不得出现任何业务 starter 的业务规则；上层也不得在自家重写本层已有的能力。
2. **R2 禁止穿透持久层**：跨模块取数只走契约；共享契约优先落在本层（`support/` 下的 SPI / 契约接口）。
3. **R4 缺能力就下沉**：上层缺少通用能力时，扩到本层，而不是在上层再造一份。**同一能力两份实现 = S0**（SPI 会命中错实现）。

## 本层职责

通用工具（HTTP、JSON、缓存、SPI、脚本/迁移、渠道上游契约如 `MagpieSupport` / `ChannelUpstreamProvider` / `ChannelHttp` 等）。新增能力先查本层是否已有同类实现。

## 渠道口径

渠道管理业务属于 `spring-support-account-starter`；本层只提供**契约与工具**，不持有渠道表、不实现渠道规则。

## 图片 AI 检测门户（2026-11-26 定版）

判断「一张图是否 AI 参与」的唯一入口 = `com.chua.common.support.ai.image.AiImageDetector`（本层）。默认实现 `HeuristicAiImageDetector`（`@SpiDefault` + `@Spi("heuristic")`）只做委托，判定口径仍由 `PhotoMetadataUtils.analyze` 决定，不得在门户里另写一套判据。

- **取实例只走 `AiImageDetector.create()` / `create(String)`**，禁止 `ServiceProvider#getPriority()`。实测：同时注册 `heuristic`（`@SpiDefault`，order 0）与一个 order 100 的实现后，`getExtension("")` 仍返回 heuristic，而 `getPriority()` 返回 order 高的那个——`@SpiDefault` 只保护默认路径，`getPriority()` 完全按 `@Spi(order)` 排序。
- 模型实现（如 onnx `deepfake-detector`）由对应引擎 starter 注册，**不得标 `@SpiDefault`、不得抬高 order 抢默认**；模型未就绪时 `available()=false` 且 `detect()` 返回 `AiImageDetectResult.unavailable(...)`，不抛异常。
- 结论用 `AiImageVerdict` 枚举而不是中文文案；`NO_AI_SIGNAL` 语义是「没发现痕迹」，**不等于「人工创作」**，上层不得渲染成「人工」。
- 启发式实现 `aiProbability` 恒 `null`（不产出校准概率）；需要概率走模型实现。

## 动态 SPI 生命周期（2026-11-26）

- OSGi 注册对象由框架持有；SPI 查询、代理调用、枚举与优先级入口必须读取当前注册视图，不得另建插件实例或永久缓存动态结果与 miss。
- `@SpiDefault` 默认选择与 `getPriority()` 排序是两个契约。框架托管的默认对象不得进入静态默认缓存或保活缓存；尤其要回归「扩展名未命中 → 默认插件 → 卸载」这条回退路径。
- 宿主桥与 resolver 统一复用 `ServiceDefinitionUtils.buildDefinitionAlias(...)`，保留 `SpiOrder`、`Spi.order`、`SpiDefault` 等元数据；上层不得再手工拼一套注解解析规则。
- 真实 Felix 的普通 bundle 事件异步投递。生命周期验收须在有界时间内等待服务可见/消失，再断言实例身份与业务结果；仅等 `Bundle.start()` 返回不足以证明发布链已完成。

## 交付闸门
不反向依赖 `spring-*`；无重复实现；`mvn -o -q compile -DskipTests` EXIT=0；编码 `BOM=0 / U+FFFD=0`。任一项不满足不得声称完成。

## 提交

只提交自己的文件；本仓库变更若影响上层，必须在同批说明中标注需要同步发布的模块。
