# P3C 审查报告 — example-starter 体系

**扫描范围**: `utils-support-parent-starter` 全量
**审查时间**: 2026-08-22
**example-starter 文件数**: 184 Java 文件
**总违规数**: 810（强制 64 / 推荐 746）

---

## 一、体系规范预检

### 1.1 跨模块边界扫描 — [强制] 越界违规

**非 example-starter 模块中出现 `*Example*.java` 类（5个）：**

| 文件 | 模块 | 问题 |
|---|---|---|
| `ExampleBundleApplication.java` | utils-support-osgi-starter | 类名含 Example，越界到生产模块 |
| `OcrOutputExample.java` | utils-support-deeplearning-onnx-starter | 类名含 Example，越界到生产模块 |
| `HlsTranscodeExample.java` | utils-support-video-processor-starter | 类名含 Example，越界到生产模块 |
| `VideoProcessorSpiExample.java` | utils-support-video-processor-starter | 类名含 Example，越界到生产模块 |
| `PrometheusExample.java` | utils-support-prometheus-starter | 类名含 Example，越界到生产模块 |

**非 example-starter 模块 pom.xml 引入演示依赖（16个）：**

| 模块 | 越界依赖 |
|---|---|
| utils-support-common-starter | junit |
| utils-support-datasource-starter | junit |
| utils-support-network-starter | junit, mockito |
| utils-support-duckdb-starter | junit |
| utils-support-datasync-agent-starter | junit |
| utils-support-datasync-starter | junit |
| utils-support-deeplearning-agentscope-starter | junit |
| utils-support-deeplearning-langchain4j-starter | junit |
| utils-support-deeplearning-needle-starter | junit |
| utils-support-deeplearning-onnx-starter | junit |
| utils-support-deeplearning-safetensors-starter | junit |
| utils-support-fory-starter | junit |
| utils-support-mock-starter | junit |
| utils-support-oshi-starter | junit |
| utils-support-gateway-server-starter | junit |
| utils-support-kcp-starter | junit |

### 1.2 example-starter 内部命名合规

| 检查项 | 结果 |
|---|---|
| 文件名以 `Example` 结尾 | ✗ **32个违规** — 含 `*Spi.java`/`*Main.java`/`*Benchmark.java`/`*Service.java` 等非 Example 后缀 |
| 类名与文件名一致 | ✗ 个别不一致（如 `ExampleBase.java` 类名为 `ExampleBase` ✓，但部分 Spi 适配器类名有差异） |
| 存在 `main` 方法 | ✗ **18个违规** — 无 main 的独立 Example 类 |
| 无 JUnit 依赖 | ✗ **2个违规** — 发现 `@Test` 注解 |

---

## 二、P3C 违规明细

### [强制] 体系命名（32条）

文件名不以 `Example` 结尾的文件（在 example-starter 内也属违规）：

- `ExampleModelPricingProvider.java` — 应改为 `ExampleModelPricingProviderExample.java`
- `ExampleBase.java` — 基类，豁免（但建议改名 `ExampleBaseClass.java`）
- `Example.java` (spi包) — SPI 接口，豁免
- `FlowEchoNode.java`, `RpcEchoService.java`, `RpcPayload.java` — 辅助类，应移至 example-starter 子包或用 `Example` 后缀
- `HttpServerBenchmark.java`, `PerfReport.java` — Benchmark 类，应改为 `*Example.java`
- `AsyncHttpPerfMain.java`, `AsyncTcpProxyPerfMain.java` 等 8个 `*Main.java` — 应改为 `*Example.java`
- `OcrDeskewCompareAll.java`, `OcrRotateCompare.java`, `VoiceCloneVerify.java` 等 — 应改为 `*Example.java`

### [强制] 体系入口（18条）

缺少 `public static void main(String[] args)` 的独立 Example：

- `LazyExpiringListExample.java`
- `SerializationBenchmarkExample.java`
- `RpcExample.java`（可能是 Spi 入口，需确认）
- 等 18 个文件

### [强制] 体系边界（2条）

- `FeatureDimensionExample.java` 等 — 发现 `@Test` 注解，应用 `assert` 替代

### [强制] 1.1 命名风格（5条）

- 变量名含下划线（如 `max_count`, `temp_list`）
- 文件：`SpeakerDiarizationExample.java`, `EngineExample.java`

### [强制] 1.2 常量定义（4条）

- long 字面量使用小写 `l` 而非 `L`
- 文件：`FileSearchExample.java`, `FlowCompleteExample.java`

### [强制] 1.6 并发处理（3条）

- 使用 `Executors.newFixedThreadPool()` 等工厂方法
- 文件：`SpeakerDiarizationExample.java`, `EngineExample.java`

### [推荐] 2.2 日志规范（675条）

**最常见违规**：106个文件使用 `System.out.println` 而非 SLF4J

Top 10 重灾区：
| 文件 | System.out.println 次数 |
|---|---|
| SpeakerDiarizationExample.java | 36 |
| EngineExample.java | 30 |
| FlowCompleteExample.java | 25 |
| FaceAllExample.java | 18 |
| AudioFingerprintExample.java | 17 |
| FaceDetectDrawExample.java | 17 |
| Seq2SeqTranslationExample.java | 16 |
| FlowExample.java | 15 |
| FileSearchExample.java | 13 |
| ZeroShotDebugExample.java | 13 |

### [推荐] 体系入口（70条）

有 main 但无 `System.exit(0/1)` 表达通过/失败：
- 70个文件，包括 `FeatureDimensionExample.java`, `YoloWorldExample.java` 等

---

## 三、体系规范预检汇总表

| 文件 | 模块 | 文件名合规 | 类名合规 | main 存在 | 无 JUnit | 边界合规 |
|---|---|---|---|---|---|---|
| WhisperAudioExample.java | example-starter | ✓ | ✓ | ✓ | ✓ | ✓ |
| QwenProxyExample.java | example-starter | ✓ | ✓ | ✓ | ✓ | ✓ |
| ExampleModelPricingProvider.java | example-starter | ✗ Provider结尾 | ✓ | ✗ 无main | ✓ | ✓ |
| ExampleBase.java | example-starter | ✗ Base结尾 | ✓ | ✓ | ✓ | ✓ |
| ExampleBundleApplication.java | **osgi-starter** | ✗ Application结尾 | — | — | — | ✗ **越界** |
| OcrOutputExample.java | **onnx-starter** | ✓ | ✓ | ✓ | ✓ | ✗ **越界** |
| HlsTranscodeExample.java | **video-processor** | ✓ | ✓ | ✓ | ✓ | ✗ **越界** |
| PrometheusExample.java | **prometheus-starter** | ✓ | ✓ | ✓ | ✓ | ✗ **越界** |

---

## 四、建议修复优先级

### P0 — [强制] 体系越界（立即修复）
1. 将 5 个越界 Example 文件移至 `example-starter` 或改名
2. 从 16 个非 example-starter 模块 pom.xml 中移除 `junit`/`mockito` 依赖（保留 test scope）

### P1 — [强制] 命名与入口（本迭代修复）
3. 32 个文件名不以 `Example` 结尾 → 重命名
4. 18 个文件缺少 main 方法 → 补充或标记为 Spi 豁免
5. 2 个文件含 `@Test` → 改用 `assert`

### P2 — [强制] P3C 规约（下迭代修复）
6. 5 个文件变量名含下划线 → camelCase
7. 4 个文件 long 字面量小写 l → 大写 L
8. 3 个文件 Executors 工厂 → ThreadPoolExecutor

### P3 — [推荐] 代码质量（逐步优化）
9. 106 个文件 675 处 `System.out.println` → SLF4J（可批量替换）
10. 70 个文件缺 `System.exit` → 补充结果表达

---

## 五、统计摘要

| 级别 | 数量 | 占比 |
|---|---|---|
| [强制] | 64 | 7.9% |
| [推荐] | 746 | 92.1% |
| **总计** | **810** | — |

**最严重前3违规：**
1. 🔴 **16个模块 pom.xml 引入 junit 演示依赖** — 违反体系边界规范
2. 🔴 **5个生产模块混入 Example 类** — 违反边界隔离
3. 🟡 **106个文件 675处 System.out.println** — 日志规范违规（推荐级但量大）
