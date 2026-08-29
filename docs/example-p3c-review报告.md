# example-starter P3C 规约审查报告

> 审查范围：`utils-support-example-starter/src/main/java/com/chua/example/`（314 个 Java 文件）
> 审查日期：2026-08-30
> 依据：`example-p3c-review` 体系规范 + 阿里巴巴 P3C 规约

---

## 一、扫描范围与统计

| 项目 | 数值 |
| --- | --- |
| 扫描文件总数 | 314 |
| 体系级违规（命名/边界/入口） | 8 |
| P3C 强制违规 | 15 |
| P3C 推荐/参考违规 | 8 |

---

## 二、体系规范预检

### 2.1 命名规范违规（[强制] 文件名必须以 `Example` 结尾）

共 **8 个文件**不符合 `*Example.java` 命名规范：

| 文件 | 违规类型 | 建议命名 |
| --- | --- | --- |
| `face/FaceIdentifyFullTest.java` | Test 结尾 | `FaceIdentifyFullExample.java` |
| `face/FacePipelineSmokeTest.java` | Test 结尾 | `FacePipelineExample.java` |
| `network/ftp/FtpServerTest.java` | Test 结尾 | `FtpServerExample.java` |
| `onnx/FaceRestoreCompareTest.java` | Test 结尾 | `FaceRestoreCompareExample.java` |
| `onnx/FaceRestorePipelineCompareTest.java` | Test 结尾 | `FaceRestorePipelineCompareExample.java` |
| `onnx/GfpganOnnxQuickTest.java` | Test 结尾 | `GfpganOnnxExample.java` |
| `tree/BTreeDebug.java` | Debug 结尾 | `BTreeExample.java` |
| `vector/VectorMathBench.java` | Bench 结尾 | `VectorMathBenchExample.java` |

**例外豁免**：
- `runner/ExampleRunner.java`（启动器，规范允许）
- `*ExampleSpi.java`（SPI 适配器，规范允许）

> **注意**：`FaceRestorePipelineCompareTest`、`FaceIdentifyFullTest` 被 `run_restore_compare.ps1`、`run_face_identify_full.ps1` 脚本引用，重命名后需同步更新脚本。

### 2.2 包名规范违规（[强制]）

- `face/FacePipelineSmokeTest.java:1` — **包名错误**：`com.chua.deeplearning.support.face`，应为 `com.chua.example.face`
  - 该文件把示例代码放在了生产模块的包路径下，违反 `com.chua.example.*` 包规范

### 2.3 入口规范检查

- 8 个命名违规文件均有 `public static void main` ✓
- 其余 Example 均符合 main + args 入口 ✓

### 2.4 边界规范检查（跨模块）

- 非 example-starter 模块 `src/main/java` 无 `*Example*`/`*Test*`/`*Verify*`/`*Diag*` 类 ✓
- 非 example-starter 模块 pom.xml 无 junit/mockito/example-starter 越界依赖 ✓
- 无 MyBatis 注解 SQL ✓
- 无 JUnit import（`@Test`/`assertEquals` 匹配均为自定义断言）✓

---

## 三、P3C 强制违规

### 3.1 [强制] 1.10 反射统一使用 ReflectUtils

直接使用 `java.lang.reflect` API，应改为 `ReflectUtils`：

- `face/FaceIdentifyFullTest.java:46`
  - **问题**: `Class.forName("...OnnxModelRegistrar")` 直接反射
  - **正例**: `ReflectUtils.forName("...OnnxModelRegistrar")`
- `face/FacePipelineSmokeTest.java:25` — 同上 `Class.forName`
- `onnx/FaceRestoreCompareTest.java:30` — 同上 `Class.forName`
- `onnx/FaceRestorePipelineCompareTest.java:65` — 同上 `Class.forName`
- `onnx/GfpganOnnxQuickTest.java:20` — 同上 `Class.forName`
- `face/FaceFullPipe3BeautyExample.java:36-53`
  - **问题**: `Class.forName` + `gfp.getMethod(...)` + `targetM.invoke(...)` 直接反射调用
  - **正例**: 用 `ReflectUtils.forName(...)` + `ReflectUtils.invoke(obj, "target", args)`
- `image/ImageProcessorApiExample.java:62-68`
  - **问题**: `type.getMethod("resize", ...)` 等直接反射
- `onnx/ModelMetricsExample.java:121`
  - **问题**: `method.invoke(provider)` 直接反射

### 3.2 [强制] System.exit 位置违规

`System.exit()` 仅允许在 `main` 方法中调用，以下在非 main 方法中调用：

- `concurrent/queue/LockFreeQueueExample.java:109` — `runAllTests` 中 `System.exit(1)`
- `concurrent/queue/LockFreeQueueExample.java:123,127` — `runSingleTest` 中 `System.exit(1)`
- `lang/IdUtilsUuidv7Example.java:162,172,191,202,215` — `verify*` 方法中 `System.exit(1)`
- `lang/TableViewParserExample.java:89` — `verify` 方法中 `System.exit(1)`
- `media/ScreenCaptureExample.java:198,204` — `runDemo` 方法中 `System.exit(1)`

**正例**: 方法应返回 `int`/`boolean` 状态码，由 `main` 统一 `System.exit(status)`。

### 3.3 [强制] 1.3 代码格式 — 单行压缩

多条语句压缩到一行：

- `concurrent/bulkhead/BulkheadExample.java:50`
  - **问题**: `Thread t = Thread.ofVirtual().start(() -> flow.execute(() -> { await(block); return "ok"; }));`
  - **正例**: lambda 体内部分行
- `concurrent/bulkhead/BulkheadExample.java:72`、`BulkheadPressureExample.java:138`、`concurrent/lock/LockExample.java:100`、`concurrent/rate/RateLimiterExample.java:64`
  - **问题**: `if (!passed) { System.out.println("[FAIL] ..."); System.exit(EXIT_CODE_FAILURE); }` 一行压缩
  - **正例**: if 体分两行
- `face/FaceAllDetectorsExample.java:70`
  - **问题**: `if (anyOk) okCount++; else failCount++;`
  - **正例**: 分行书写
- `vector/VectorMathBench.java:12,14`
  - **问题**: `for (...) { a[i] = ...; b[i] = ...; }` 循环体多语句同行

### 3.4 [强制] 1.3 代码格式 — 无空格压缩（VectorMathBench）

- `vector/VectorMathBench.java:15-23`
  - **问题**: `int i=0;i<count;i++`、`long t0=System.nanoTime()` 缺少运算符空格
  - **正例**: `int i = 0; i < count; i++`、`long t0 = System.nanoTime();`

### 3.5 [强制] 1.8 注释规约 — 类 Javadoc 缺失/空洞

- `vector/VectorMathBench.java:6`
  - **问题**: 类无 Javadoc（无 @author/@since）
- `onnx/GfpganOnnxQuickTest.java:10`
  - **问题**: 类注释空洞 `/** Quick GFPGAN ONNX smoke test. */`，缺 @author/@since

### 3.6 [强制] 敏感信息硬编码路径

- `face/FacePipelineSmokeTest.java:17`
  - **问题**: 硬编码 `G:\\images\\三个人.jpg` 绝对路径（非 `D:\images` 统一测试目录）
  - **正例**: 使用 args 参数传入或统一 `D:\images` 目录

---

## 四、推荐/参考违规

| 文件 | 违规 | 级别 |
| --- | --- | --- |
| `vector/VectorMathBench.java:9,13` | 魔法值 `64/256/512/384/1024`、`5_000_000/500_000` 未提取常量 | 推荐 |
| `vector/VectorMathBench.java:12` | `Math.random()` 未 `import static` | 推荐 |
| `onnx/GfpganOnnxQuickTest.java:15-16` | 魔法值 `"D:/images/3peoplebeauty.jpg"`、`"D:/images/output"` | 推荐 |
| `face/FacePipelineSmokeTest.java:17-19` | 魔法值常量 `TEST_IMAGE` 等硬编码 | 推荐 |
| `concurrent/bulkhead/BulkheadExample.java:61,65` 等 | `try {...} catch` 单行压缩 | 参考 |
| 各 Example 中 `System.out.println` 结构化输出 | 规范允许（[PASS]/[FAIL]/[TIME]/[DONE] 标记），日志细节建议 SLF4J | 参考 |

---

## 五、修复建议优先级

### P0（命名 + 包名 + 反射 + System.exit，强制）

1. **重命名 8 个违规文件**为 `*Example.java` 结尾，并同步更新 `run_restore_compare.ps1`、`run_face_identify_full.ps1` 脚本中的类名引用。
2. **修复 `FacePipelineSmokeTest` 包名**为 `com.chua.example.face`。
3. **替换 `Class.forName`** 为 `ReflectUtils.forName`（涉及 7 个文件）。
4. **移动 `System.exit` 到 main**：`LockFreeQueueExample`、`IdUtilsUuidv7Example`、`TableViewParserExample`、`ScreenCaptureExample` 改为返回状态码。

### P1（格式，强制）

5. 修复单行压缩（`Bulkhead*`、`LockExample`、`RateLimiterExample`、`FaceAllDetectorsExample`、`VectorMathBench`）。
6. 补全 `VectorMathBench`、`GfpganOnnxQuickTest` 的类 Javadoc。

### P2（推荐）

7. 魔法值提取常量、`import static` 优化。

---

## 六、涉及文件清单

| 类别 | 文件 |
| --- | --- |
| 命名违规 | 8 个（见 2.1 表） |
| 包名违规 | `FacePipelineSmokeTest.java` |
| 反射违规 | `FaceIdentifyFullTest`、`FacePipelineSmokeTest`、`FaceRestoreCompareTest`、`FaceRestorePipelineCompareTest`、`GfpganOnnxQuickTest`、`FaceFullPipe3BeautyExample`、`ImageProcessorApiExample`、`ModelMetricsExample` |
| System.exit 位置 | `LockFreeQueueExample`、`IdUtilsUuidv7Example`、`TableViewParserExample`、`ScreenCaptureExample` |
| 单行压缩 | `BulkheadExample`、`BulkheadPressureExample`、`LockExample`、`RateLimiterExample`、`FaceAllDetectorsExample`、`VectorMathBench` |
| 注释/魔法值 | `VectorMathBench`、`GfpganOnnxQuickTest`、`FacePipelineSmokeTest` |

---

## 七、最严重前 3 违规

1. **`FacePipelineSmokeTest` 包名错误**（`com.chua.deeplearning.support.face`）——示例代码污染生产包路径，且硬编码 `G:\` 路径。
2. **`VectorMathBench` 完全不符合规范**——无 Javadoc、命名违规、格式压缩、魔法值、无 import static，建议重写为 `VectorMathBenchExample`。
3. **`GfpganOnnxQuickTest` 命名 + 反射 + 空洞注释**——历史调试遗留，建议重命名并规范。
