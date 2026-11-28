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
- **扁平路径阈值与临界带必须成对调整（2026-11-26 定版）**：`FLAT_STD_MIN` / `FLAT_SAT_MIN` 现为 8/8，且 `tex ∈ [0.20, 0.25]` 内谱峰缺失时会补跑一次扁平路径（命中仍只算「疑似」）。放宽这两个闸门的唯一凭据是「界面截图负样本误报数 = 0」——实测近灰度截图 sat 仅 1.4~4.4，`sat ≥ 8` 仍有 2 倍余量；把 sat 降到 4 以下会立刻把这类截图打成 AI，禁止再降。照片路径「谱峰为主证据」的结论不变（绝区零人类插画 ρ=0.989 仍须判「未发现」）。
- **C2PA 清单字段提取（2026-11-26 定版）**：PNG `caBX`、JPEG APP11、WebP `C2PA` 三种承载统一走 `parseC2pa(byte[])` → `C2paInfo`，对 JUMBF 做**不建树的宽松 CBOR 文本扫描**（只解码 major type 3 文本串，`isPrintableText` 过滤二进制误命中），读 `digitalSourceType` / `softwareAgent{name,version}` / `claim_generator_info{name}` / 旧版 `claim_generator`。**两个安全阀不可去掉**：① `softwareAgent` 只在来源类型属 AI 或代理名命中 `GENERATOR_STRONG` 时才算生成器（否则相机/修图软件签发的凭证会被误报成 AI）；② 取不到的字段一律留空，禁止拼猜。`when` 与 `c2pa.actions` 未透出（`Report` 契约无对应字段）。

## 动态 SPI 生命周期（2026-11-26）

- OSGi 注册对象由框架持有；SPI 查询、代理调用、枚举与优先级入口必须读取当前注册视图，不得另建插件实例或永久缓存动态结果与 miss。
- `@SpiDefault` 默认选择与 `getPriority()` 排序是两个契约。框架托管的默认对象不得进入静态默认缓存或保活缓存；尤其要回归「扩展名未命中 → 默认插件 → 卸载」这条回退路径。
- 宿主桥与 resolver 统一复用 `ServiceDefinitionUtils.buildDefinitionAlias(...)`，保留 `SpiOrder`、`Spi.order`、`SpiDefault` 等元数据；上层不得再手工拼一套注解解析规则。
- 真实 Felix 的普通 bundle 事件异步投递。生命周期验收须在有界时间内等待服务可见/消失，再断言实例身份与业务结果；仅等 `Bundle.start()` 返回不足以证明发布链已完成。

## AgentScope 离线验证与路径边界（2026-11-28 实测）

- **SDK 2.0.4 的 `LocalFilesystem` ROOTED 模式不能视为符号链接隔离边界**：根内链接指向探针自有的区外目录时，真实读写均成功；普通 `..`、区外绝对路径和兄弟目录被拒，不能抵消此缺口。产品 `SessionWorkspaceResolver` 当次仅源码审查，未做直接执行验证；不得据此宣称产品或部署路径已验收。
- HITL 恢复必须保存完整 `ToolUseBlock` 与 replyId；同批已放行工具保存为 `ALLOWED`，避免部分批准后重复裁决。真实 SDK 参数校验读取原始 JSON `content`，离线构造调用时只有 `input` 不够；文件工具返回的 JSON 文本须解码后再比较换行内容。
- 离线恢复验证可用仅覆写 `isReturnDirect()` 的包装器委托真实 SDK 文件工具，避免工具结束后进入模型/网络调用；此结果不代表 HTTP `/v2/chat/resume`、确认卡或 194 部署已通过。证据与未完成项见 [AI控制台Agent能力验收 §12](D:/ch/验收报告/AI控制台Agent能力验收.md)。

## 交付闸门
不反向依赖 `spring-*`；无重复实现；`mvn -o -q compile -DskipTests` EXIT=0；编码 `BOM=0 / U+FFFD=0`。任一项不满足不得声称完成。

## 提交

只提交自己的文件；本仓库变更若影响上层，必须在同批说明中标注需要同步发布的模块。
