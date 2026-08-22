## P3C 审查报告

**扫描范围**: `utils-support-example-starter/src/main/java/` + 全模块边界扫描
**文件数**: ~160（example-starter）
**违规数**: 22（强制 10 / 推荐 12）

---

### 一、[强制] 体系越界违规

**规则**: Example 代码、测试类只允许存在于 `utils-support-example-starter` 模块。非 example-starter 模块的 `src/main/java` 禁止出现 `*Example*` 类。

| # | 文件 | 违规 |
|---|---|---|
| 1 | `utils-support-middleware-parent/utils-support-prometheus-starter/src/main/java/com/chua/prometheus/support/example/PrometheusExample.java` | **越界**: non-example-starter 的 `src/main/java` 存在 `PrometheusExample` 类 |
| 2 | `utils-support-network-parent/utils-support-video-processor-starter/src/main/java/com/chua/video/processor/support/example/HlsTranscodeExample.java` | **越界**: non-example-starter 的 `src/main/java` 存在 `HlsTranscodeExample` 类 |
| 3 | `utils-support-network-parent/utils-support-video-processor-starter/src/main/java/com/chua/video/processor/support/example/VideoProcessorSpiExample.java` | **越界**: non-example-starter 的 `src/main/java` 存在 `VideoProcessorSpiExample` 类 |

**pom.xml 依赖检查**: 上述模块的 pom.xml 均未意外引入 `junit`/`mockito`/`h2`/`example-starter` 依赖 → 仅类文件越界，无依赖污染。

---

### 二、[强制] 命名风格违规

**规则**: 文件名必须以 `Example` 结尾，不允许 `Test`/`Verify`/`Diag`/`Debug` 等后缀；类名须与文件名一致。

| # | 文件 | 问题 | 反例 | 正例 |
|---|---|---|---|---|
| 4 | `example/onnx/VoiceCloneVerify.java` | 文件名以 `Verify` 结尾 | `VoiceCloneVerify.java` | `VoiceCloneExample.java` |
| 5 | `example/onnx/OcrTextDump.java` | 文件名不含 `Example` 后缀 | `OcrTextDump.java` | `OcrTextDumpExample.java` |
| 6 | `example/onnx/OcrRotateCompare.java` | 文件名不含 `Example` 后缀 | `OcrRotateCompare.java` | `OcrRotateCompareExample.java` |
| 7 | `example/network/perf/PerfReport.java` | 文件名不含 `Example` 后缀 | `PerfReport.java` | `PerfReportExample.java` |
| 8 | `example/network/rpc/RpcEchoService.java` | 文件名不含 `Example` 后缀 | `RpcEchoService.java` | `RpcEchoServiceExample.java` |
| 9 | `example/network/rpc/RpcEchoServiceImpl.java` | 文件名不含 `Example` 后缀 | `RpcEchoServiceImpl.java` | `RpcEchoServiceImplExample.java` |
| 10 | `example/network/rpc/RpcPayload.java` | 文件名不含 `Example` 后缀 | `RpcPayload.java` | `RpcPayloadExample.java` |
| 11 | `example/network/rpc/RpcServerMain.java` | 文件名不含 `Example` 后缀 | `RpcServerMain.java` | `RpcServerMainExample.java` |
| 12 | `example/network/sip/SipServerMain.java` | 文件名不含 `Example` 后缀 | `SipServerMain.java` | `SipServerMainExample.java` |
| 13 | `example/network/http/HttpServerBenchmark.java` | 文件名不含 `Example` 后缀 | `HttpServerBenchmark.java` | `HttpServerBenchmarkExample.java` |
| 14 | `example/network/http/HttpServerBenchmarkMain.java` | 文件名不含 `Example` 后缀 | `HttpServerBenchmarkMain.java` | `HttpServerBenchmarkMainExample.java` |
| 15 | `example/network/proxy/AsyncHttpPerfMain.java` | 文件名不含 `Example` 后缀 | `AsyncHttpPerfMain.java` | `AsyncHttpPerfMainExample.java` |
| 16 | `example/network/proxy/AsyncHttpProxyPerfMain.java` | 文件名不含 `Example` 后缀 | `AsyncHttpProxyPerfMain.java` | `AsyncHttpProxyPerfMainExample.java` |
| 17 | `example/network/proxy/AsyncServerPerfMain.java` | 文件名不含 `Example` 后缀 | `AsyncServerPerfMain.java` | `AsyncServerPerfMainExample.java` |
| 18 | `example/network/proxy/AsyncTcpProxyPerfMain.java` | 文件名不含 `Example` 后缀 | `AsyncTcpProxyPerfMain.java` | `AsyncTcpProxyPerfMainExample.java` |
| 19 | `example/network/proxy/AsyncTcpServerPerfMain.java` | 文件名不含 `Example` 后缀 | `AsyncTcpServerPerfMain.java` | `AsyncTcpServerPerfMainExample.java` |
| 20 | `example/network/proxy/JdkTcpPerfMain.java` | 文件名不含 `Example` 后缀 | `JdkTcpPerfMain.java` | `JdkTcpPerfMainExample.java` |
| 21 | `example/network/proxy/VertxHttpProxyPerfMain.java` | 文件名不含 `Example` 后缀 | `VertxHttpProxyPerfMain.java` | `VertxHttpProxyPerfMainExample.java` |
| 22 | `example/network/proxy/VertxTcpPerfMain.java` | 文件名不含 `Example` 后缀 | `VertxTcpPerfMain.java` | `VertxTcpPerfMainExample.java` |
| 23 | `example/network/proxy/VertxTcpProxyPerfMain.java` | 文件名不含 `Example` 后缀 | `VertxTcpProxyPerfMain.java` | `VertxTcpProxyPerfMainExample.java` |

---

### 三、[强制] 编译错误 — 反引号类名

| # | 文件 | 问题 |
|---|---|---|
| 24 | `example/pipeline/PipelineBasicExample.java` | **编译错误**: 类名使用反引号 `` `PipelineBasicExample `` 包裹，Java 不允许反引号作为类名标识符，将导致编译失败 |
|    |    | 反例: `` public class `PipelineBasicExample` implements Example { `` |
|    |    | 正例: `public class PipelineBasicExample implements Example {` |

---

### 四、[推荐] 注释规约缺失 `@author`

| # | 文件 | 问题 |
|---|---|---|
| 25 | `example/onnx/ZeroShotDebugExample.java` | 缺少 `@author` 标签 |
| 26 | `example/onnx/OcrTextDump.java` | 缺少 `@author` 标签 |
| 27 | `example/onnx/OcrRotateCompare.java` | 缺少 `@author` 标签 |
| 28 | `example/network/http/HttpServerBenchmarkMain.java` | 缺少 `@author` 标签 |

---

### 五、[推荐] 日志规范 — 应使用 SLF4J

**规则**: 日志细节应使用 SLF4J `log`，禁止 `System.out.println` 做日志输出。结构化 `[PASS]`/`[FAIL]` 标记允许 `System.out.println`。

| # | 文件 | 问题 |
|---|---|---|
| 29 | `example/onnx/ZeroShotDebugExample.java` | 全部使用 `System.out.println`，无 SLF4J `log` |
| 30 | `example/onnx/VoiceCloneVerify.java` | 全部使用 `System.out.println`，无 SLF4J `log` |
| 31 | `example/onnx/OcrTextDump.java` | 多处 `System.out.println`，应改用 `log` |
| 32 | `example/pipeline/PipelineBasicExample.java:68` | `System.out.println("[PipelineBasicExample] type=" + ...)` 字符串拼接，应用 `log.info("type={}", type)` |

---

### 六、体系规范预检汇总

| 文件 | 模块 | 文件名合规 | 类名合规 | main 存在 | 无 JUnit | 边界合规 |
|---|---|---|---|---|---|---|
| PipelineBasicExample.java | example-starter | ✅ | ⚠️ 反引号 | ✅ | ✅ | ✅ |
| VoiceCloneVerify.java | example-starter | ✗ Verify | ⚠️ 类名含Verify | ✅ | ✅ | ✅ |
| ZeroShotDebugExample.java | example-starter | ✅ | ✅ | ✅ | ✅ | ✅ |
| OcrTextDump.java | example-starter | ✗ 缺Example | ✅ | ✅ | ✅ | ✅ |
| OcrRotateCompare.java | example-starter | ✗ 缺Example | ✅ | ✅ | ✅ | ✅ |
| PerfReport.java | example-starter | ✗ 缺Example | ✅ | ❌ | ✅ | ✅ |
| HttpServerBenchmarkMain.java | example-starter | ✗ 缺Example | ✅ | ✅ | ✅ | ✅ |
| PrometheusExample.java | prometheus-starter | ✅ | ✅ | ✅ | ✅ | ✗ **越界** |
| HlsTranscodeExample.java | video-processor-starter | ✅ | ✅ | ✅ | ✅ | ✗ **越界** |
| VideoProcessorSpiExample.java | video-processor-starter | ✅ | ✅ | — | ✅ | ✗ **越界** |

---

### 七、最严重的 3 个违规

1. **[强制] 编译错误** — `PipelineBasicExample.java` 类名含反引号 `` `PipelineBasicExample ``，导致整个 example-starter 模块无法编译，阻塞所有运行。
2. **[强制] 体系越界** — `PrometheusExample.java` 在 `prometheus-starter` 的 `src/main/java` 中，生产包携带示例代码。
3. **[强制] 体系越界** — `HlsTranscodeExample.java` 和 `VideoProcessorSpiExample.java` 同样越界在 `video-processor-starter` 的 `src/main/java` 中。

---

### 八、建议修复优先级

1. **立即修复（[强制] 编译错误）**: 修正 `PipelineBasicExample.java` 中的反引号类名。
2. **立即修复（[强制] 体系越界）**: 将 `PrometheusExample.java`、`HlsTranscodeExample.java`、`VideoProcessorSpiExample.java` 迁移到 `utils-support-example-starter`，或从 production 代码中移除。
3. **批量重命名（[强制] 命名规范）**: 对 example-starter 中所有不含 `Example` 后缀的文件重命名（共约 20 个）。
4. **补充注释（[推荐]）**: 为缺少 `@author` 的 4 个类补充 Javadoc。
5. **日志替换（[推荐]）**: 将 `ZeroShotDebugExample`、`VoiceCloneVerify`、`OcrTextDump` 中的 `System.out.println` 替换为 SLF4J `log`。
