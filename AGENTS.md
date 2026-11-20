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

## 交付闸门

不反向依赖 `spring-*`；无重复实现；`mvn -o -q compile -DskipTests` EXIT=0；编码 `BOM=0 / U+FFFD=0`。任一项不满足不得声称完成。

## 提交

只提交自己的文件；本仓库变更若影响上层，必须在同批说明中标注需要同步发布的模块。
