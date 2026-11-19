# utils-support-zstd-starter

> utils 体系的 **zstd 压缩扩展模块**：为 SIP 数据面的流式压缩 SPI（`SipStreamCompressor`）提供基于 zstd-jni 的原生 zstd 实现，作为默认 GZIP 压缩器之外的替换选项。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.common.support.network.sip`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-zstd-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `SipStreamCompressor` 的 zstd SPI 实现（`@Spi("zstd-sip-stream-compressor")`） | 不是通用压缩工具门面，也不是压缩算法库 |
| 能力 | 把任意 `OutputStream` 包装为 zstd 压缩流、把任意 `InputStream` 包装为 zstd 解压流 | 不提供 gzip/lz4/snappy 等其它算法，不做字典训练、多线程压缩等 zstd 高级调参 |
| 依赖 | 只依赖 `utils-support-common-starter`（SPI 契约）、`zstd-jni`、`slf4j-api` | 不依赖 Spring，无自动配置、无配置文件 |
| 归属 | SIP 数据面压缩的**实现方**；SPI 接口与编排契约在 `utils-support-common-starter` | 不包含 SIP 服务端、隧道、认证、加密 |

### 1.3 规模

- Java 源文件 **1** 个：`com.chua.common.support.network.sip.ZstdSipStreamCompressor`（`@since 4.0.0.43`，作者 `CH`）。
- 无 `src/main/resources`（因此也没有 `META-INF/extensions` 登记文件），无测试源码。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 取 zstd SIP 流压缩器 | `ServiceProvider.of(SipStreamCompressor.class).getExtension("zstd-sip-stream-compressor")` | 按 SPI 名获取本模块实现 |
| 直接实例化 | `new ZstdSipStreamCompressor()` | 无状态实现，可不走 SPI |
| 压缩输出流 | `compressor.wrap(out)` | 返回 `ZstdOutputStream`，写入明文 → 压缩 → 落到 `out` |
| 解压输入流 | `compressor.unwrap(in)` | 返回 `ZstdInputStream`，读取压缩帧 → 解压 → 交给上层 |
| 压缩器名称 | `compressor.name()` | 返回 `"zstd-sip-stream-compressor"` |
| 取默认压缩器 | `ServiceProvider.of(SipStreamCompressor.class).getDefault()` | 默认实现为 common-starter 的 `SipStreamCompressor.Gzip`（JDK GZIP，`BEST_SPEED`，name 为 `"gzip"`） |
| 在 SIP 配置中指定 zstd | `SipConfig.builder().compress(true).compressor("zstd-sip-stream-compressor").build()` | `SipConfig.compressor` 字段即对应 `SipStreamCompressor` 的 SPI 标识 |

### 2.2 ZstdSipStreamCompressor 方法

| 方法 | 签名 | 行为 |
| --- | --- | --- |
| `wrap` | `OutputStream wrap(OutputStream out) throws IOException` | 包装为 `new ZstdOutputStream(out)`；`out == null` 时抛 `IOException("SIP Zstd 目标输出流不能为空")` |
| `unwrap` | `InputStream unwrap(InputStream in) throws IOException` | 包装为 `new ZstdInputStream(in)`；`in == null` 时抛 `IOException("SIP Zstd 输入流不能为空")` |
| `name` | `String name()` | 返回 `"zstd-sip-stream-compressor"` |

两个包装方法在「zstd 原生库加载失败或流创建失败」时抛 `IOException`（见源码 javadoc）。

### 2.3 SPI 契约：SipStreamCompressor（接口在 common-starter）

| 项 | 值 |
| --- | --- |
| 接口 | `com.chua.common.support.network.sip.SipStreamCompressor`（`@Spi("sip-stream-compressor")`，`@since 4.0.0.42`） |
| 契约 | `wrap(OutputStream)`：明文 → 压缩 → 原始流；`unwrap(InputStream)`：压缩帧 → 解压 → 上层；`name()`：SPI 名称，用于日志/诊断 |
| 默认实现 | `SipStreamCompressor.Gzip`（`@SpiDefault`）：JDK `GZIPOutputStream`/`GZIPInputStream`，压缩级别 `Deflater.BEST_SPEED`，低延迟优先 |
| 组合顺序 | 按接口 javadoc：与 `SipTunnelStream` 通过 `SipConfig.compress` 开关组合；与 AES-GCM（`SipConfig.encrypt`）正交，组合顺序为 socket → encrypt → compress → app |
| 压缩器选择 | `SipConfig` 提供 `compress`（是否开启压缩）与 `compressor`（压缩器 SPI 名称）字段，`Builder` 对应 `compress(boolean)` / `compressor(String)` |

---

## 3. 快速开始

### 3.1 最短可运行示例：zstd 压缩/解压一个字符串

```java
import com.chua.common.support.network.sip.SipStreamCompressor;
import com.chua.common.support.spi.ServiceProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ZstdDemo {
    public static void main(String[] args) throws Exception {
        // 1. 从 SPI 取 zstd 实现（本模块在 classpath 上即可被同包扫描发现）
        SipStreamCompressor compressor = ServiceProvider.of(SipStreamCompressor.class)
                .getExtension("zstd-sip-stream-compressor");
        System.out.println(compressor.name());   // zstd-sip-stream-compressor

        // 2. wrap：把普通输出流包装成 zstd 压缩流
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (OutputStream zip = compressor.wrap(buffer)) {
            zip.write("hello zstd".getBytes(StandardCharsets.UTF_8));
        }

        // 3. unwrap：把压缩数据包装成解压输入流，还原原文
        try (InputStream unzip = compressor.unwrap(new ByteArrayInputStream(buffer.toByteArray()))) {
            System.out.println(new String(unzip.readAllBytes(), StandardCharsets.UTF_8));
            // hello zstd
        }
    }
}
```

> 本模块的实现是**对称**的：`wrap` 写出的 zstd 帧只能被同一实现（或兼容的 zstd 解压器）`unwrap` 还原。

### 3.2 在 SIP 数据面启用 zstd 压缩

```java
import com.chua.common.support.network.sip.SipConfig;

SipConfig config = SipConfig.builder()
        .compress(true)                              // 开启数据面压缩
        .compressor("zstd-sip-stream-compressor")    // 指定压缩器 SPI 名称
        .build();
```

按 `SipStreamCompressor` 接口 javadoc 的描述，SIP 数据面的组合顺序为 socket → encrypt → compress → app；`compressor` 字段即用于从 SPI 中选择具体压缩器实现。

---

## 4. 配置项

**无。** 依据：

- 本模块没有 `src/main/resources`，不存在任何 `application*.yml` / `*.properties` 配置文件；
- `pom.xml` 未引入 Spring Boot 相关依赖，也没有自动配置类或 `@ConfigurationProperties`；
- 唯一的类 `ZstdSipStreamCompressor` 没有任何配置字段，压缩/解压参数完全使用 zstd-jni 流的默认值，本模块未暴露调节入口。

与压缩开关相关的 `SipConfig.compress` / `SipConfig.compressor` 属于 `utils-support-common-starter` 的 SIP 配置模型，不是本模块的配置项。

---

## 5. SPI 扩展点

本模块是 SPI **实现方**；扩展点（`SipStreamCompressor` 接口）定义在 `utils-support-common-starter`（路径 `../../utils-support-core-parent/utils-support-common-starter/`）。

**本实现如何被加载：**

1. 实现类标注 `@Spi("zstd-sip-stream-compressor")`；
2. `SipStreamCompressor` 接口位于 `com.chua.common.support.network.sip` 包，本实现类与接口**同包**；
3. SPI 的 `SamePackageServiceResolver` 会扫描接口所在包及其子包下的具体实现类，因此**无需** `META-INF/extensions` 登记文件——只要本模块 jar 在 classpath 上，`ServiceProvider.of(SipStreamCompressor.class)` 即可按名取到它。

**想提供自己的压缩器**（例如 lz4/snappy）：

```java
@Spi("my-sip-stream-compressor")
public class MySipStreamCompressor implements SipStreamCompressor {
    @Override public OutputStream wrap(OutputStream out) throws IOException { /* ... */ }
    @Override public InputStream unwrap(InputStream in) throws IOException { /* ... */ }
    @Override public String name() { return "my-sip-stream-compressor"; }
}
```

放在与接口相同的包（或其子包）下即可被同包扫描发现；也可以按 common-starter SPI 的另一种策略，在 `META-INF/extensions/com.chua.common.support.network.sip.SipStreamCompressor` 中登记 `名称=实现类全限定名`。

---

## 6. 构建与依赖

### 6.1 依赖清单（来自本模块 pom.xml）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | `SipStreamCompressor` SPI 契约、`@Spi` 注解等基础能力 |
| `com.github.luben:zstd-jni` | `1.5.7-21`（根 pom `zstd-jni.version` 统一管理，模块不写死） | zstd 原生压缩/解压流（JNI 封装，内嵌多平台原生库） |
| `org.slf4j:slf4j-api` | 父 pom 管理 | 日志门面 |

### 6.2 聚合状态

`utils-support-extra-parent/pom.xml` 当前的 `<modules>` 列表**未包含** `utils-support-zstd-starter`，因此聚合构建（`mvn ...` 在仓库根或 extra-parent 目录）不会编到本模块；请单独进入本模块目录构建，或在父 pom 中补充 `<module>utils-support-zstd-starter</module>`。

### 6.3 构建验证

模块目录内保留有构建日志 `output.txt`，记录了一次真实构建结果：

```text
--- compiler:3.15.0:compile (default-compile) @ utils-support-zstd-starter ---
Compiling 1 source file with javac [forked debug release 25] to target\classes
BUILD SUCCESS
```

本地构建命令（在 `utils-support-zstd-starter` 目录下）：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

- **通用压缩/归档门面**：zip / tar 等文件级压缩在 `utils-support-common-starter` 的 `FileSystem`；压缩包预览解压在 `utils-support-filestorage-starter`。
- **其它压缩算法**：gzip 默认实现（`SipStreamCompressor.Gzip`）在 common-starter；lz4 / snappy 等本模块未提供。
- **zstd 高级 API**：字典训练、多线程压缩参数、`Zstd.compress` 等 zstd-jni 高级能力未透出；本模块只做流式包装。
- **SIP 协议栈**：SIP 服务端、隧道（`SipTunnelStream`）、认证、加密（AES-GCM `SipCipher`）均在 `utils-support-common-starter`，本模块只负责压缩这一层。
- **Spring 自动配置 / 配置项 / 指标**：源码未提供，本模块是纯 SPI 实现包。
