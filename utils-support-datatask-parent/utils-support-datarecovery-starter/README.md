# utils-support-datarecovery-starter

> 数据恢复（Data Recovery）starter：基于 Rust FFI（JNI）的设备扫描、已删除文件发现、文件恢复与安全擦除能力的**装配模块**。本模块自身不含 Java 源码，提供 Maven 依赖坐标与 GraalVM native-image 反射配置；JNI 门面类 `com.chua.datarecovery.support.DataRecovery` 与四平台原生动态库由 `utils-support-native-datarecovery` 模块提供。
>
> 版本：`4.0.0.51`（继承父 POM `utils-support-datatask-parent`）｜JDK：`25`（继承仓库根 POM `java.version=25`）｜模块自有包名：无（本模块无 Java 源码）；门面类包名：`com.chua.datarecovery.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 行为说明与已知问题](#7-行为说明与已知问题)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)
- [9. 文档索引](#9-文档索引)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datarecovery-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 数据恢复能力的 starter 装配模块：Maven 坐标 + GraalVM native-image 反射注册 | 不含 Java 业务源码 |
| 门面 | 唯一入口是 `com.chua.datarecovery.support.DataRecovery`（JNI 桥接 Rust `data_recovery_ffi`） | 不提供第二个门面，不允许绕过它直连原生符号 |
| 能力 | 目录树扫描（walkdir）、原始磁盘签名 carving、NTFS MFT 已删除文件元数据、文件恢复导出、随机数覆写式安全删除 | 不是备份工具，不做文件系统挂载，不做 RAID/卷管理 |
| 依赖 | 只依赖 `utils-support-common-starter`、`jackson-databind`、`lombok`（provided） | 不依赖 Spring，不依赖数据库驱动，不依赖其它业务 starter |
| 原生库 | Rust cdylib `data_recovery_ffi`，随 jar 内 `native/` 资源分发四平台产物 | 本模块不编译 Rust；Rust 源码与 `build.sh` 在 `utils-support-native-datarecovery` |

### 1.3 模块内容物

本模块 `src/main` 下**没有 Java 源码**，仅包含：

| 文件 | 作用 |
| --- | --- |
| `pom.xml` | 依赖坐标：`utils-support-common-starter`、`jackson-databind`、`lombok`（provided） |
| `src/main/resources/META-INF/native-image/com.chua/utils-support-datarecovery-starter/reflect-config.json` | 为 `DataRecovery` 及其 6 个嵌套类型注册全量反射（native-image 必需） |
| `src/main/resources/META-INF/native-image/com.chua/utils-support-datarecovery-starter/native-image.properties` | `Args = --initialize-at-build-time=com.chua.datarecovery.support` |
| `数据恢复测试报告.md` | 三种扫描模式的测试矩阵、性能对比与已知问题 |

> 说明：`DataRecovery.java` 原先位于本模块，为让 JNI 绑定与动态库同住而迁入 `utils-support-native-datarecovery`；因全限定名 `com.chua.datarecovery.support.DataRecovery` 保持不变，调用方无需改动。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建实例 | `DataRecovery.of(devicePath)` | 静态工厂；裸盘符（如 `F:`、`F:\`）自动转为设备路径 `\\.\F:`，完整路径与 `\\.\` / `\\?\` / `/dev/` 前缀原样保留 |
| 注册回调 | `dr.callback(callback)` | 链式；进度 / 发现文件 / 已恢复 / 出错四个回调 |
| 目录树扫描 | `dr.scan(0)` | walkdir 模式，无需管理员权限 |
| 原始磁盘签名扫描 | `dr.scan(1)` | raw disk carve，需管理员权限；无输出目录时原生侧默认限制 500MB |
| NTFS 已删除文件元数据 | `dr.scan(2)` | NTFS MFT 模式，需管理员权限 |
| 扫描并直接恢复 | `dr.scanAndRecover(scanMode, outputDir)` | carve 出的文件写入输出目录 |
| 恢复指定文件 | `dr.recover(filePaths, outputDir, preserveStructure)` | 按路径列表恢复，可保留目录结构 |
| 安全删除 | `dr.permanentDelete(filePath, method)` | 随机数覆写后删除文件；method 见 2.5 |
| 异步执行 | `dr.scanAsync(...)` / `dr.recoverAsync(...)` / `dr.deleteAsync(...)` | 返回 `CompletableFuture` |

### 2.2 `DataRecovery` 公开方法

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `of(String devicePath)` | `DataRecovery` | 静态工厂，构造函数私有 |
| `callback(RecoveryCallback)` | `DataRecovery` | 链式注册回调 |
| `scan(int scanMode)` | `ScanResult` | 扫描；不存在的普通路径在调原生前即返回 `success=false` |
| `scanAndRecover(int scanMode, String outputDir)` | `ScanResult` | 扫描并把恢复文件写入 `outputDir` |
| `recover(String[] filePaths, String outputDir, boolean preserveStructure)` | `RecoverResult` | 恢复指定文件列表 |
| `permanentDelete(String filePath, String method)` | `DeleteResult` | 覆写式安全删除 |
| `scanAsync(int)` / `recoverAsync(String[], String, boolean)` / `deleteAsync(String, String)` | `CompletableFuture<...>` | 对应同步方法的异步包装 |

阶段名常量（回调 `onProgress` 的 `stage` 参数）：`scan`、`scanAndRecover`、`recover`、`permanentDelete`。

### 2.3 扫描模式 `scanMode`

| 值 | 模式 | 权限要求 | 扫描范围 | 适用场景 | 速度 |
| :--- | :----- | :-------- | :-------- | :---- | :---- |
| 0 | walkdir | 无 | 目录树 | 正常文件签名识别 | 快（文件数相关） |
| 1 | raw disk carve | 管理员 | 原始磁盘（默认 500MB） | 已删除文件签名恢复 | 中（约 33s/500MB） |
| 2 | NTFS MFT | 管理员 | NTFS MFT | 已删除文件元数据 | 中（约 30s/全盘） |

其它取值：原生侧按 unknown mode 处理，回退为 walkdir。

### 2.4 回调 `RecoveryCallback`

四个方法均需实现（接口无 default 方法）：

| 方法 | 触发时机 |
| --- | --- |
| `onProgress(String stage, int percent)` | 各操作开始时 `(阶段, 0)`、结束时 `(阶段, 100)` |
| `onFileFound(FileEntry entry)` | 扫描类操作对每条结果触发 |
| `onRecovered(String path, long bytes)` | `scanAndRecover` 对每条结果触发；`recover` / 删除不触发 |
| `onError(String error)` | 失败或存在失败项时触发；触发后仍按原语义返回失败结果或抛出异常 |

回调全部由 Java 侧触发，原生侧不接受回调参数。

### 2.5 永久删除 `method` 取值

| method | 覆写次数 |
| :------- | :---- |
| `dod` | 3 次 |
| `gutmann` | 35 次 |
| `simple` | 1 次 |
| 其它值 | 1 次（默认） |

删除流程：以 4096 字节缓冲写入随机数据覆写整个文件（累计 `bytesOverwritten`、`passesCompleted`），随后删除文件。

### 2.6 结果对象字段

均为 `DataRecovery` 的公开静态嵌套类，字段为 public；原生 JSON 以蛇形命名返回，Java 侧用 Jackson `SNAKE_CASE` 策略映射。

| 类 | 字段 |
| --- | --- |
| `FileEntry` | `name`、`path`、`sizeBytes`、`modifiedTimestamp`、`deletedTimestamp`、`recoveryScore`、`carvedSignature` |
| `ScanResult` | `success`、`filesScanned`、`filesFound`、`message`、`entries`（`FileEntry[]`） |
| `RecoverResult` | `success`、`message`、`successCount`、`failedCount`、`failedList`（`FailedItem[]`）、`totalBytesWritten` |
| `FailedItem` | `path`、`reason` |
| `DeleteResult` | `success`、`bytesOverwritten`、`passesCompleted`、`message` |
| `RecoveryException` | `RuntimeException` 子类；JSON 解析失败时抛出（`Parse JSON failed: ...`） |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datarecovery-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>

<!-- DataRecovery 门面类与平台 native 资源；版本跟随 native-parent 发布线。 -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-native-datarecovery</artifactId>
    <version>4.0.0.43</version>
</dependency>
```

`DataRecovery` 类与四平台动态库由同级仓库 `utils-support-native-datarecovery` 提供（见第 6 节）。两个依赖都需要位于 classpath；本 starter 只提供装配资源和 native-image 反射配置。native artifact 当前版本为 `4.0.0.43`，不要将其机械替换为 starter 的 `4.0.0.51`。

### 3.2 最短可运行示例：目录树扫描

```java
import com.chua.datarecovery.support.DataRecovery;
import com.chua.datarecovery.support.DataRecovery.ScanResult;

ScanResult result = DataRecovery.of("D:/data").scan(0);
if (result.success) {
    System.out.println("scanned=" + result.filesScanned + ", found=" + result.filesFound);
    for (DataRecovery.FileEntry entry : result.entries) {
        System.out.println(entry.path + " (" + entry.sizeBytes + " bytes)");
    }
}
```

### 3.3 带回调的扫描与恢复

```java
import com.chua.datarecovery.support.DataRecovery;

DataRecovery dr = DataRecovery.of("F:")
        .callback(new DataRecovery.RecoveryCallback() {
            @Override public void onProgress(String stage, int percent) {
                System.out.println(stage + " " + percent + "%");
            }
            @Override public void onFileFound(DataRecovery.FileEntry entry) {
                System.out.println("found: " + entry.path);
            }
            @Override public void onRecovered(String path, long bytes) {
                System.out.println("recovered: " + path + " (" + bytes + " bytes)");
            }
            @Override public void onError(String error) {
                System.err.println("error: " + error);
            }
        });

// NTFS MFT：列出已删除文件元数据（需管理员权限）
DataRecovery.ScanResult deleted = dr.scan(2);

// 原始磁盘 carving 并直接恢复到输出目录（需管理员权限）
DataRecovery.ScanResult carved = dr.scanAndRecover(1, "G:/recovered_files");
```

### 3.4 恢复指定文件 / 安全删除 / 异步

```java
// 恢复指定文件，保留目录结构
DataRecovery.RecoverResult rr =
        dr.recover(new String[]{"F:/docs/a.png"}, "G:/out", true);
System.out.println(rr.successCount + " ok, " + rr.failedCount + " failed");

// 覆写式安全删除
DataRecovery.DeleteResult del = dr.permanentDelete("D:/tmp/secret.txt", "dod");

// 异步扫描
dr.scanAsync(0).thenAccept(r -> System.out.println(r.filesFound));
```

---

## 4. 配置项

**无**。依据：

- 本模块无 Java 源码，无 Spring 自动装配类，无 `@ConfigurationProperties`，仓库与模块内均不存在 `application*.yml` / `application*.properties` 配置键；
- 模块内唯一的非代码配置是 GraalVM native-image 的 `reflect-config.json` 与 `native-image.properties`（`--initialize-at-build-time=com.chua.datarecovery.support`），属于构建期元数据，不是应用配置项；
- 原生侧 `FileScanner.max_scan_bytes`（`scanner.rs`，raw disk 扫描上限）存在于 Rust 源码，但 Java 门面 `nativeScan(devicePath, scanMode)` 未暴露该参数，调用方无可配置入口。

行为基线（源码口径，非配置）：原生 `ScanResult.success` / `message` 为硬编码（扫描正常完成时 `success` 恒为 `true`，`message` 为以 `"Scan completed"` 开头的固定文案，不反映底层真实错误），因此 `DataRecovery` 在调用原生前先做目标校验——非设备路径且不存在时直接返回 `success=false`，不调原生；设备路径（`\\.\` / `\\?\` / `/dev/`）无法用文件 API 判断，仍交由原生处理。

---

## 5. SPI 扩展点

**无**。依据：本模块无 Java 源码，未定义任何 SPI 接口，`src/main/resources/META-INF/extensions/` 目录不存在；原生能力通过 JNI 直接绑定 `com.chua.datarecovery.support.DataRecovery`，且 JNI 导出符号 `Java_com_chua_datarecovery_support_DataRecovery_*` 已把全限定名编译进二进制，**包名与类名不可更改**（改则 `UnsatisfiedLinkError`，除非同步重建四平台 Rust 库）。

---

## 6. 构建与依赖

### 6.1 Maven 依赖

```
utils-support-datarecovery-starter
├── com.chua:utils-support-common-starter   （${project.version}；提供 NativeUtils 等）
├── com.fasterxml.jackson.core:jackson-databind （结果 JSON 解析）
└── org.projectlombok:lombok                （provided）
```

> 门面类 `com.chua.datarecovery.support.DataRecovery` 与四平台动态库位于 `utils-support-native-parent/utils-support-native-datarecovery`（版本 4.0.0.43）；该模块同样依赖 `utils-support-common-starter` 与 `jackson-databind`。运行期由 `NativeUtils.loadFromClasspath("data_recovery_ffi")` 从 classpath 加载动态库。

### 6.2 原生库（Rust）

Rust 源码与 JNI 绑定均由 `utils-support-native-datarecovery` 提供，本模块不含任何原生源码：

- Rust 工程：`utils-support-native-datarecovery/src/main/rust/`（`Cargo.toml`、`Cargo.lock`、`build.sh`）
- Rust 源码：`src/main/rust/src/` 下 `lib.rs`（JNI 导出）、`scanner.rs`（walkdir / raw disk / MFT 三模式）、`signatures.rs`（文件签名）、`recoverer.rs`、`eraser.rs`（安全删除）、`carver/signature_carver.rs`、`fs/{ntfs,fat,ext4}.rs`、`disk/{windows,linux,macos}.rs`
- 四平台产物：`utils-support-native-datarecovery/src/main/resources/native/`
  - `windows-x86_64/data_recovery_ffi.dll`
  - `linux-x86_64/libdata_recovery_ffi.so`
  - `darwin-x86_64/libdata_recovery_ffi.dylib`
  - `darwin-aarch64/libdata_recovery_ffi.dylib`

### 6.3 GraalVM native-image

- `reflect-config.json` 注册 `DataRecovery` 与嵌套类型 `FileEntry`、`ScanResult`、`DeleteResult`、`RecoverResult`、`FailedItem`、`RecoveryCallback` 的全量构造器 / 方法 / 字段反射；
- `native-image.properties`：`Args = --initialize-at-build-time=com.chua.datarecovery.support`。

---

## 7. 行为说明与已知问题

以下摘自模块内《数据恢复测试报告.md》（更新日期 2026-07-28，作者 CH）：

**修复记录**

1. `normalizeDevicePath` 只转换裸盘符：仅当路径为纯盘符格式（`F:` 或 `F:\`）时才转为 `\\.\F:`，完整路径（如 `C:\Users\...`）保持原样，避免 walkdir 模式误扫整个盘根目录；
2. raw disk scan 默认上限：无 `max_scan_bytes` 时默认限制为 500MB，避免无输出目录时扫满全盘导致超时。

**已知问题**

1. `ScanTest` mode 1 超时：无输出目录时 raw disk scan 会超时（120s），即使已限制 500MB，建议改用 `DataRecoveryTest` 场景；
2. JPEG 签名不匹配：walkdir 模式只读前 12 字节，JPEG 的 footer（`FF D9`）不在头部内，导致无法匹配；
3. carve 文件大小固定：所有恢复文件大小均为上限值（PNG=5MB，GIF=10MB），因 footer 边界检测在原始磁盘上不准确。

**测试覆盖矩阵（2026-07-28 执行记录）**

| 场景ID | 测试类 | 模式 | 目标 | 结果 | 耗时 |
| :------ | :------- | :----- | :----- | :----- | :---- |
| DR-01 | ScanTest | 0-walkdir | 测试目录（3 files，含 PNG/JPEG） | 通过：4 scanned, 1 found (PNG) | 189ms |
| DR-02 | ScanTest | 2-NTFS MFT | F:（Admin） | 通过：10 deleted files found | 31.5s |
| DR-03 | ScanTest | 1-raw disk carve | F:（Admin） | 失败：超时（120s） | - |
| DR-04 | RecoverTest | 1-raw disk carve+recover | F: -> G:\recovered_files | 失败：超时（120s） | - |
| DR-05 | DataRecoveryTest | 0-walkdir | 测试目录 | 通过：1 file found (PNG) | 213ms |
| DR-06 | DataRecoveryTest | 1-raw disk carve+recover | F: -> G:\recovered_files（Admin） | 通过：116 files carved, 740MB | 32.9s |
| DR-07 | DataRecoveryTest | 2-NTFS MFT | F:（Admin） | 通过：10 deleted files found | 29.4s |

---

## 8. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `DataRecovery` 门面类、JNI 绑定、四平台动态库、Rust 源码与构建脚本 | `utils-support-native-parent/utils-support-native-datarecovery` |
| `NativeUtils`（动态库加载）、`FileSystem`、任务调度/流程等通用工具 | `utils-support-core-parent/utils-support-common-starter` |
| Spring 自动装配、Web 端点、Controller | 本模块及门面类均不含 |
| 备份 / 恢复任务编排（backup、restore 流程） | 属于 common-starter 的任务子系统，本模块只提供底层扫描/恢复/擦除原语 |
| 业务侧「数据恢复」功能（页面、API、审计等） | 上层业务模块自行编排，本模块不持有业务规则 |
