## P3C 审查报告

**扫描范围**: `utils-support-example-starter/src/main/java/` + 跨模块边界检查
**文件数**: ~160（example-starter）+ 全量跨模块扫描
**违规数**: 21（强制 9 / 推荐 12 / 参考 0）

---

### 一、体系规范预检 — 边界越界（[强制]）

**规则**: Example 代码、测试类只允许存在于 `utils-support-example-starter` 模块。其他模块的 `src/main/java` 禁止出现 `*Example*` / `*Test*` / `*Verify*` / `*Diag*` 类。

| # | 文件 | 违规 |
|---|---|---|
| 1 | `utils-support-middleware-parent/utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/example/PrometheusExample.java` | **越界**: non-example-starter 的 `src/main/java` 存在 `*Example*` 类 |
| 2 | `utils-support-network-parent/utils-support-video-processor-starter/src/main/java/com/chua/video/processor/support/example/HlsTranscodeExample.java` | **越界**: non-example-starter 的 `src/main/java` 存在 `*Example*` 类 |
| 3 | `utils-support-network-parent/utils-support-video-processor-starter/src/main/java/com/chua/video/processor/support/example/VideoProcessorSpiExample.java` | **越界**: non-example-starter 的 `src/main/java` 存在 `*Example*` 类 |

**pom.xml 依赖检查**（非 example-starter 模块是否引入演示库）:
- `utils-support-network-starter` pom: junit/mockito 均为 `<scope>test</scope>` → ✅ 合规
- `utils-support-prometheus-starter` pom: 无演示库引入 → ✅ 合规
- `utils-support-video-processor-starter` pom: 无演示库引入 → ✅ 合规

---

### 二、命名风格（[强制]）

**规则**: 文件名必须以 `Example` 结尾，不允许 `Test`、`Verify`、`Diag`、`Debug` 等后缀。SPI 适配器保留 `*ExampleSpi.java` 命名。

| # | 文件 | 问题 | 正例 |
|---|---|---|---|
| 4 | `example/onnx/VoiceCloneVerify.java` | 文件名以 `Verify` 结尾 | `VoiceCloneExample.java` |
| 5 | `example/onnx/ZeroShotDebugExample.java` | 类名含 `Debug`，应体现功能而非调试用途 | `ZeroShotDebugExample` → 可保留（含 Example 后缀），但 [推荐] 改名消除调试语义 |
| 6 | `example/onnx/OcrTextDump.java` | 文件名不含 `Example` 后缀 | `OcrTextDumpExample.java` |
| 7 | `example/onnx/OcrRotateCompare.java` | 文件名不含 `Example` 后缀 | `OcrRotateCompareExample.java` |
| 8 | `example/network/perf/PerfReport.java` | 文件名不含 `Example` 后缀 | `PerfReportExample.java` |
| 9 | `example/network/rpc/RpcEchoService.java` | 文件名不含 `Example` 后缀 | `RpcEchoServiceExample.java` |
| 10 | `example/network/rpc/RpcEchoServiceImpl.java` | 文件名不含 `Example` 后缀 | `RpcEchoServiceImplExample.java` |
| 11 | `example/network/rpc/RpcPayload.java` | 文件名不含 `Example` 后缀 | `RpcPayloadExample.java` |
| 12 | `example/network/rpc/RpcServerMain.java` | 文件名不含 `Example` 后缀 | `RpcServerMainExample.java` |
| 13 | `example/network/http/HttpServerBenchmark.java` | 文件名不含 `Example` 后缀 | `HttpServerBenchmarkExample.java` |
| 14 | `example/network/http/HttpServerBenchmarkMain.java` | 文件名不含 `Example` 后缀 | `HttpServerBenchmarkMainExample.java` |
| 15 | `example/network/proxy/AsyncHttpPerfMain.java` | 文件名不含 `Example` 后缀 | `AsyncHttpPerfMainExample.java` |
| 16 | `example/network/proxy/AsyncHttpProxyPerfMain.java` | 文件名不含 `Example` 后缀 | `AsyncHttpProxyPerfMainExample.java` |
| 17 | `example/network/proxy/AsyncServerPerfMain.java` | 文件名不含 `Example` 后缀 | `AsyncServerPerfMainExample.java.java` |
| 18 | `example/network/proxy/AsyncTcpProxyPerfMain.java` | 文件名不含 `Example` 后缀 | `AsyncTcpProxyPerfMainExample.java` |
| 19 | `example/network/proxy/AsyncTcpServerPerfMain.java` | 文件名不含 `Example` 后缀 | `AsyncTcpServerPerfMainExample.java` |
| 20 | `example/network/proxy/JdkTcpPerfMain.java` | 文件名不含 `Example` 后缀 | `JdkTcpPerfMainExample.java` |
| 21 | `example/network/proxy/VertxHttpProxyPerfMain.java` | 文件名不含 `Example` 后缀 | `VertxHttpProxyPerfMainExample.java` |
| 22 | `example/network/proxy/VertxTcpPerfMain.java` | 文件名不含 `Example` 后缀 | `VertxTcpPerfMainExample.java` |
| 23 | `example/network/proxy/VertxTcpProxyPerfMain.java` | 文件名不含 `Example` 后缀 | `VertxTcpProxyPerfMainExample.java` |
| 24 | `example/network/sip/SipServerMain.java` | 文件名不含 `Example` 后缀 | `SipServerMainExample.java` |

---

### 三、注释规约（[推荐]）

**规则**: 类必须有 `@author` 和 `@since` Javadoc。

| # | 文件 | 问题 |
|---|---|---|
| 25 | `example/onnx/ZeroShotDebugExample.java` | 缺少 `@author` 标签 |
| 26 | `example/onnx/OcrTextDump.java` | 缺少 `@author` 标签 |
| 27 | `example/onnx/OcrRotateCompare.java` | 缺少 `@author` 标签 |
| 28 | `example/network/http/HttpServerBenchmarkMain.java` | 缺少 `@author` 标签 |

---

### 四、日志规范（[推荐]）

**规则**: 禁止直接 `System.out.println` 输出日志，使用 SLF4J `log.info()`。Example 中 `[PASS]`/`[FAIL]` 结构化输出允许 `System.out.println`，但日志细节应用 SLF4J。

| # | 文件 | 问题 |
|---|---|---|
| 29 | `example/pipeline/PipelineBasicExample.java:68` | `System.out.println("[PipelineBasicExample] type=" + ...)` — 使用字符串拼接 |
| 30 | `example/pipeline/PipelineBasicExample.java:164` | `System.out.println(([PASS]/[FAIL]) + name)` — 允许的结构化输出，但建议统一用 log |
| 31 | `example/onnx/ZeroShotDebugExample.java:6` | 全部使用 `System.out.println`，应改用 `log` |
| 32 | `example/onnx/VoiceCloneVerify.java` | 全部使用 `System.out.println`，应改用 `log` |
| 33 | `example/onnx/OcrTextDump.java` | 多处 `System.out.println`，应改用 `log` |

---

### 五、体系规范预检 — 汇总

| 文件/模块 | 文件名合规 | 类名合规 | main 存在 | 无 JUnit | 边界合规 |
|---|---|---|---|---|---|
| `PipelineBasicExample.java` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `VoiceCloneVerify.java` | ✗ Verify结尾 | ✗ 含Verify | ✅ | ✅ | ✅ |
| `ZeroShotDebugExample.java` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `OcrTextDump.java` | ✗ 缺Example后缀 | ✅ | ✅ | ✅ | ✅ |
| `OcrRotateCompare.java` | ✗ 缺Example后缀 | ✅ | ✅ | ✅ | ✅ |
| `PerfReport.java` | ✗ 缺Example后缀 | ✅ | — | ✅ | ✅ |
| `HttpServerBenchmarkMain.java` | ✗ 缺Example后缀 | ✅ | ✅ | ✅ | ✅ |
| `PrometheusExample.java` | ✅ | ✅ | ✅ | ✅ | ✗ **非example-starter** |
| `HlsTranscodeExample.java`（video-processor） | ✅ | ✅ | ✅ | ✅ | ✗ **非example-starter** |
| `VideoProcessorSpiExample.java`（video-processor） | ✅ | ✅ | — | ✅ | ✗ **非example-starter** |

---

### 六、最严重的 3 个违规

1. **[强制] 体系越界** — `PrometheusExample.java` 在 `prometheus-starter` 的 `src/main/java` 中存在，破坏了模块化边界，生产包会携带示例代码。
2. **[强制] 体系越界** — `HlsTranscodeExample.java` 和 `VideoProcessorSpiExample.java` 同样越界在 `video-processor-starter` 的 `src/main/java` 中。
3. **[强制] 命名违规** — `VoiceCloneVerify.java` 类名以 `Verify` 结尾，违反体系命名规范。

---

### 七、建议修复优先级

1. **立即修复（[强制] 体系越界）**: 将 `PrometheusExample.java`、`HlsTranscodeExample.java`、`VideoProcessorSpiExample.java` 迁移到 `utils-support-example-starter`，或从 production 代码中移除。
2. **批量重命名（[强制] 命名规范）**: 对 example-starter 中所有不含 `Example` 后缀的文件进行重命名。
3. **补充注释（[推荐]）**: 为缺少 `@author` 的类补充注释。
4. **日志替换（[推荐]）**: 将 `System.out.println` 逐步替换为 SLF4J `log`。
