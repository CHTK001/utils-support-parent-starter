# utils-support-deeplearning-laya-starter

> Laya **本地概率决策**集成模块：基于 laya-java（`io.github.pig-mesh.ai:laya-java`，JVM 内 ONNX Runtime 前向推理）提供 `NOUL / CHOICE / SCORE` 三型类型化决策，经 SPI 同时提供两个实现——决策客户端 `DecisionClient`（provider=`laya`）与 AI 决策规则构造器 `RuleBuilder`（名称=`ai`）。
>
> 版本：`4.0.0.51`｜JDK：`25`｜包名：`com.chua.deeplearning.support.laya`｜Maven：`com.chua:utils-support-deeplearning-laya-starter`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 三种决策问题类型](#4-三种决策问题类型)
- [5. AI 决策规则](#5-ai-决策规则)
- [6. 配置项](#6-配置项)
- [7. SPI 扩展点](#7-spi-扩展点)
- [8. 构建与依赖](#8-构建与依赖)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 模块定位

本模块是 `utils-support-deeplearning-parent` 下的一个 starter，把 laya-java 的本地概率决策引擎适配成 common-starter 的两套契约：

| SPI 契约（common-starter） | 本模块实现 | SPI 名称 | 说明 |
| --- | --- | --- | --- |
| `com.chua.common.support.ai.decision.DecisionClient` | `LayaDecisionClient` | `laya` | 一次前向推理联合回答一批决策问题 |
| `com.chua.common.support.ai.rule.RuleBuilder` | `LayaAiRuleBuilder` | `ai` | 构造 `LayaAiRule`（`RuleType.AI` 规则） |

模块只有 3 个主类：

| 类 | 引入版本 | 职责 |
| --- | --- | --- |
| `LayaDecisionClient` | 4.0.0.42 | `DecisionClient` 的 laya 实现：`DecisionQuery` → laya `Question`，`LayaEngine#predict` 一次前向，答案转回 `DecisionBatch` |
| `LayaAiRuleBuilder` | 4.0.0.43 | AI 规则构造器：声明决策问题与期望结论，`build()` 产出 `LayaAiRule` |
| `LayaAiRule` | 4.0.0.43 | AI 规则（record，纯数据）：`matched(RuleContext)` 时按需向 laya 底座求值并判定命中 |

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-laya-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | laya-java 本地概率决策的 SPI 适配层：决策客户端 + AI 规则底座 | 不是远程决策服务客户端，也不是通用 AI 网关 |
| 能力 | NOUL / CHOICE / SCORE 三型问题的批量前向推理、答案转 `DecisionBatch`、AI 规则声明与命中判定 | 不做模型训练 / 微调 / 量化，不下载或管理模型资产 |
| 形态 | 纯 Java 库 + Java SPI（`META-INF/services`、`META-INF/extensions`），附 GraalVM `native-image` 的 `reflect-config.json` | 不是 Spring Boot starter：无自动配置类、无 `application.*` 配置键、无 HTTP 端点 |
| 依赖 | 核心依赖为 `utils-support-common-starter` 的契约与 laya-java / onnxruntime（另需 slf4j-api 与 provided 的 lombok，完整清单见第 8 节） | 不依赖任何 `spring-*` 或业务 starter |

**判断口径**：决策协议本身（`DecisionClient` / `DecisionQuery` / `Decision` / `DecisionBatch` 等）与规则协议（`Rule` / `RuleSet` / `RuleExecutor` 等）都定义在 `utils-support-common-starter`；本模块只提供「laya」这一本地推理底座的实现。

---

## 2. 核心 API 与速查表

### 2.1 常见能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建 laya 决策客户端 | `DecisionClient.create("laya", modelPath)` | 也可 `DecisionClient.create(DecisionSetting)`；provider 留空时默认即 `laya` |
| 一次问一批问题 | `client.decide(state, List<DecisionQuery>)` | 同一上下文联合求值，返回 `DecisionBatch` |
| 是 / 否判断 | `DecisionQuery.noul(id, question[, criteria])` | 答案标签 `true` / `false`（P(true)≥0.5 判 `true`），置信度 = 所选标签的概率 |
| 多选一 | `DecisionQuery.choice(id, question, options)` | 返回选中标签 + 各选项概率分布 |
| 有序分级 | `DecisionQuery.score(id, question, levels)` | 2~10 级；取 argmax 档位作结论 |
| 声明 AI 规则 | `new LayaAiRuleBuilder(id).query(query, expect, threshold)...build()` | SPI 名 `ai`；也可经 `RuleType.AI.builder(id)` 获取 |
| 判定规则是否命中 | `LayaAiRule#matched(RuleContext)` | 本地条件 AND AI 概率判定 |
| 复用 / 释放引擎 | 同模型目录进程级共享 `LayaEngine` | `close()` 只解除本客户端引用，不关闭共享引擎 |

### 2.2 关键类型（契约均在 common-starter，实现在本模块）

| 类型 | 角色 |
| --- | --- |
| `DecisionSetting` | 客户端配置：`provider`（默认 `laya`）、`modelPath`、`threads`、`calibrationPath`，Builder 模式 |
| `DecisionQuery` | 单个决策问题：`id` / `type` / `question` + 按类型互斥的 `options` / `levels` / `criteria` |
| `DecisionBatch` | 一次求值的全部答案：`decision(id)`、`labels()`、`flatten()`、`answers(request)` 交叉校验 |
| `Decision` | 单个答案：`queryId` / `type` / `label` / `probabilities`，派生方法 `confidence()`、`isTrue()` |
| `DecisionRequest` | `DecisionRequest.of(state, queries)`，供 `DecisionProvider#decide` 批量入口 |
| `AiRuleQuery` | 规则声明的决策问题 + 期望结论 `expect` + 置信度阈值 `threshold` |
| `RuleContext` | 规则求值上下文：五层变量（vars / current / external / input / environment） |
| `RuleEffect` | 命中语义：`NONE` / `ALLOW` / `DENY` / `ERROR` / `HALT` |

异常：配置错误抛 `IllegalArgumentException`；推理与协议错误抛 `DecisionException`；规则求值错误抛 `RuleException`。

---

## 3. 快速开始

### 3.1 准备模型资产目录

本地推理需要一个模型资产目录，目录下必须包含三个文件（对应 laya-java-model-v0.1.0）：

```text
/models/laya-java-model/
├── config.json
├── model.onnx
└── tokenizer.json
```

目录不存在时，首次推理会抛 `DecisionException("模型目录不存在: ...")`。

### 3.2 决策客户端：一次前向回答三个问题

```java
import com.chua.common.support.ai.decision.Decision;
import com.chua.common.support.ai.decision.DecisionBatch;
import com.chua.common.support.ai.decision.DecisionClient;
import com.chua.common.support.ai.decision.DecisionQuery;

DecisionBatch batch = DecisionClient.create("laya", "/models/laya-java-model")
        .decide("重复扣款了，请退款。", List.of(
                DecisionQuery.choice("department", "Which department should handle this message?",
                        Map.of("billing", "invoices, payments, refunds",
                               "support", "bugs, outages, system errors")),
                DecisionQuery.score("urgency", "How urgent is this request?",
                        List.of("not urgent", "soon", "critical")),
                DecisionQuery.noul("refund", "Does the customer ask for money back?")));
```

读取结果：

```java
Decision refund = batch.decision("refund");
refund.label();          // "true" / "false"
refund.confidence();     // 所选标签的置信度（NOUL 为 max(P(true), 1-P(true))）

batch.labels();          // {问题id -> 结论标签}
batch.flatten();         // {id, id.confidence, id.probabilities} 三类变量，可直接喂给规则 when
```

`DecisionClient` 继承 `AutoCloseable`，可配合 try-with-resources 使用；`close()` 之后再次 `decide` 会抛 `DecisionException`。

> CHOICE 的 `options` 顺序有位置意义，建议传入有序 Map（如 `LinkedHashMap`），不要用 `Map.of`。

### 3.3 规则方式：把决策变成一条 AI 规则

```java
import com.chua.common.support.ai.decision.DecisionQuery;
import com.chua.common.support.ai.rule.AiRule;
import com.chua.common.support.ai.rule.RuleContext;
import com.chua.deeplearning.support.laya.LayaAiRuleBuilder;

AiRule rule = new LayaAiRuleBuilder("refund-intent")
        .message("客户明确要求退款")
        .query(DecisionQuery.noul("refund", "客户是否要求退款？"), "true", 0.8)
        .modelPath("/models/laya-java-model")
        .build();

boolean hit = rule.matched(RuleContext.of("重复扣款了，请退款。"));
```

也可以走 SPI 获取构造器：`RuleType.AI.builder("refund-intent")`（内部按名称 `ai` 装配到本模块的 `LayaAiRuleBuilder`），或 `LayaAiRuleBuilder.ai()`。

---

## 4. 三种决策问题类型

`DecisionQuery` 的三种类型与 laya 的问题类型一一对应，答案统一收敛为「结论标签 + 完整概率分布」：

| 类型 | 工厂方法 | laya 问题 | 答案（`Decision`） |
| --- | --- | --- | --- |
| `NOUL`（是/否） | `noul(id, question)` / `noul(id, question, criteria)` | `noul` | `label` = `true`/`false`（P(true)≥0.5 判 `true`）；分布为 `{true: P, false: 1-P}`；`confidence()` = 所选标签的概率 |
| `CHOICE`（多选一） | `choice(id, question, options)`，选项 ≤ 255 | `choice` | `label` = 选中选项键；`probabilities` = 各选项概率 |
| `SCORE`（有序分级） | `score(id, question, levels)`，2~10 级 | `score` | laya 答案是期望值（浮点下标），本模块对分布取 **argmax** 档位标签作结论；分布键按下标映射回 `levels` 元素 |

取值与精度约定（源码行为）：

- 概率分布**只做钳位（[0,1]）、不做归一化**，原样保留模型输出的 softmax 置信度；分布之和必须大于 0。
- `NaN` 概率按 0 处理。
- NOUL 可带 `criteria`（键只能是 `true` / `false`），作为结论说明透传给 laya。
- `DecisionBatch#flatten()` 为每个答案产出 `<id>`（标签）、`<id>.confidence`（置信度）、`<id>.probabilities`（完整分布）三个变量。

---

## 5. AI 决策规则

`LayaAiRuleBuilder`（SPI 名 `ai`）与 `LayaAiRule` 一一对应：**一个构造器只允许 `build()` 一次**，复用抛 `RuleException`。决策问题在构造期只做声明（必须至少一个），真正的 AI 求值发生在规则 `matched` 时。

### 5.1 构造器方法

| 方法 | 说明 |
| --- | --- |
| `id(String)` | 规则标识，`build()` 前必填；同时作为默认显示名，不可改为其他值 |
| `name(String)` / `description(String)` / `message(String)` | 显示名 / 描述 / 命中后对外说明的原因 |
| `when(String)` / `when(Predicate<RuleContext>)` | 本地前置条件，与 AI 命中是 **AND** 关系；表达式在构造期经 `RuleExpression.of` 编译，语法错误立即暴露 |
| `then(Consumer<RuleContext>)` | 命中后按声明顺序执行的动作 |
| `to(String)` | 下一条规则标识，至多指定一次 |
| `var(String, Object)` | 规则变量，落在 `RuleContext` 最高优先级层 |
| `salience(int)` | 优先级，数值越大越先求值 |
| `effect(RuleEffect)` | 命中语义，`null` 按 `NONE` 处理 |
| `query(DecisionQuery)` / `query(query, expect)` / `query(query, expect, threshold)` | 声明决策问题；`expect` 为期望结论标签，`threshold` 为置信度阈值（0.0~1.0）；问题 `id` 不可重复 |
| `queries(List<AiRuleQuery>)` | 批量声明 |
| `state(Object)` | AI 决策上下文；为 null 时使用执行期 `context.input()` |
| `modelPath(String)` / `threads(Integer)` / `calibrationPath(String)` | 透传给 `DecisionSetting`；`threads` 非 null 时必须为正数 |
| `build()` | 构造 `LayaAiRule`；缺失 id 或没有任何决策问题时抛 `RuleException` |

### 5.2 命中判定语义

`LayaAiRule#matched(RuleContext)`：

1. 先逐个测试本地条件（`when`），任一不成立直接返回 `false`（本地是前置过滤，AI 是概率判定）；
2. 把声明的全部 `AiRuleQuery` 组成一次 `DecisionRequest`，向 laya 底座求值，并经 `DecisionBatch#answers(request)` 交叉校验；
3. `hit(answers, flatten)` 逐个检查问题：答案缺失 → 不命中；声明了 `expect` 但标签不等 → 跳过；声明了 `threshold` 但 `<id>.confidence` 低于阈值 → 跳过；任一问题通过即命中。

决策结果**不写回上下文**（避免污染后续规则的变量层）；规则实例是纯数据、不持有执行期状态，可并发复用。底座客户端在每次 `matched` 中按需创建、用完关闭。

异常包装：求值过程中的 `RuntimeException` 会被包装为 `RuleException("AI 规则 <id> 决策求值失败: ...")`。

### 5.3 多规则编排

规则聚合、优先级排序与执行由 common-starter 的 `RuleSet` / `RuleExecutor` 承担（`RuleSet` 按 `salience` 降序稳定排序、同一集合内规则类型不可混用）。本模块只负责单条 AI 规则的构造与命中判定。

---

## 6. 配置项

本模块**没有** `application.*` 配置键——它不是 Spring Boot starter，源码中没有任何配置绑定类；全部配置通过编程式传入（`DecisionSetting` 或 `LayaAiRuleBuilder` 同名方法）：

| 配置 | 传入位置 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `provider` | `DecisionSetting.provider` | 否 | `laya` | SPI 装配名；留空时 `DecisionClient.create` 也回退 `laya` |
| `modelPath` | `DecisionSetting.modelPath` / `LayaAiRuleBuilder#modelPath` | **是** | 无 | 模型资产目录（须为已存在的目录，含 `config.json` / `model.onnx` / `tokenizer.json`）；缺失时构造期抛 `IllegalArgumentException` |
| `threads` | `DecisionSetting.threads` / `LayaAiRuleBuilder#threads` | 否 | `4` | 推理线程数，必须为正数 |
| `calibrationPath` | `DecisionSetting.calibrationPath` / `LayaAiRuleBuilder#calibrationPath` | 否 | 无 | 外部校准文件路径，对应 laya-java 的 `LayaEngine#loadCalibration`；不设置则用模型资产自带校准 |
| `state` | `LayaAiRuleBuilder#state` | 否 | 执行期 `input` | AI 决策上下文对象 |

`DecisionSetting` 用 Builder 构建：

```java
DecisionSetting setting = DecisionSetting.builder()
        .provider("laya")
        .modelPath("/models/laya-java-model")
        .threads(4)
        .build();
DecisionClient client = DecisionClient.create(setting);
```

---

## 7. SPI 扩展点

### 7.1 本模块注册的实现

| SPI 接口 | 名称 | 实现 | 注册文件 |
| --- | --- | --- | --- |
| `com.chua.common.support.ai.decision.DecisionClient` | `laya` | `com.chua.deeplearning.support.laya.LayaDecisionClient` | `META-INF/extensions/com.chua.common.support.ai.decision.DecisionClient` 与 `META-INF/services/...` |
| `com.chua.common.support.ai.rule.RuleBuilder` | `ai` | `com.chua.deeplearning.support.laya.LayaAiRuleBuilder` | `META-INF/extensions/com.chua.common.support.ai.rule.RuleBuilder` |

获取方式：

```java
// 决策客户端（推荐走工厂）
DecisionClient client = DecisionClient.create("laya", modelPath);

// AI 规则构造器：枚举入口 / SPI 直取 / 静态工厂，三者都返回新实例
RuleBuilder b1 = RuleType.AI.builder("rule-id");
RuleBuilder b2 = ServiceProvider.of(RuleBuilder.class).getNewExtension("ai");
RuleBuilder b3 = LayaAiRuleBuilder.ai();
```

> 注：`ServiceProvider#getExtension(name)` 返回的是 SPI 懒加载缓存的**共享实例**，而规则构造器一次性使用，故此处用 `getNewExtension(name)` 每次取新实例（`RuleType.AI.builder` 内部同样走 `getNewExtension`）。

### 7.2 引擎复用与生命周期

`LayaDecisionClient` 内部按「模型目录绝对路径 + threads + calibrationPath」做键，把 `LayaEngine` 缓存在进程级 `ConcurrentHashMap` 中：同一模型目录的多个客户端共享同一引擎实例，避免重复加载权重。`close()` 只把自身引用置空并标记关闭，**不会**关闭共享引擎。`LayaEngine` 自身方法已同步，本模块再用实例锁串行化「懒加载 + 推理」组合操作。

### 7.3 GraalVM Native Image

`src/main/resources/META-INF/native-image/com.chua/utils-support-deeplearning-laya-starter/reflect-config.json` 已注册反射元数据：`LayaDecisionClient`、`LayaAiRuleBuilder`、`LayaAiRule`、`DecisionSetting`（构造器 / 方法 / 字段全开）。

---

## 8. 构建与依赖

模块位于 `utils-support-parent-starter/utils-support-deeplearning-parent/utils-support-deeplearning-laya-starter`，随父 POM `utils-support-deeplearning-parent`（4.0.0.51）构建。

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 POM | 决策与规则契约（`ai.decision` / `ai.rule`）、SPI 基础设施、`Wraps` / `StringUtils` 等工具 |
| `io.github.pig-mesh.ai:laya-java` | `0.1.0` | 本地 Laya 决策推理（ONNX Runtime + DJL tokenizers + Jackson） |
| `com.microsoft.onnxruntime:onnxruntime` | `1.30.0` | **显式声明**：laya-java 传递的 onnxruntime 1.24.2 已被排除，与本仓 onnx-starter 的 1.30.0 共存会冲突原生库 |
| `org.slf4j:slf4j-api` | 随父 POM | 日志 |
| `org.projectlombok:lombok` | provided | 编译期 |

编译验证（仓库口径，离线）：

```bash
mvn -o -q compile -DskipTests
```

---

## 9. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| 决策协议与契约（`DecisionClient` / `DecisionQuery` / `Decision` / `DecisionBatch` / `DecisionSetting` / `DecisionRequest` / `DecisionException` / `DecisionType`） | `utils-support-common-starter` |
| 规则协议与编排（`Rule` / `RuleBuilder` / `AiRule` / `AiRuleBuilder` / `RuleSet` / `RuleExecutor` / `RuleContext` / `RuleEffect` / `RuleType` / `RuleExpression`） | `utils-support-common-starter` |
| NATIVE（自然）规则实现 | `utils-support-common-starter`（`NativeRule` / `DefaultNativeRuleBuilder`） |
| 对话 / RAG / 向量等其他 AI 能力 | `utils-support-common-starter` 及各 cloud / deeplearning starter |
| 模型权重、tokenizer 文件本身，以及模型下载 / 训练 / 微调 | 不在本模块；只加载本地已准备好的资产目录 |
| 远程概率决策服务（JEV） | 已于 2026-09-27 退役（见 `utils-support-deeplearning-parent/pom.xml` 注释）；通用决策 SPI 仍在 common-starter，可独立使用 |
| ONNX 通用推理（自定义模型的前向 / 会话管理） | `utils-support-deeplearning-onnx-starter` |
