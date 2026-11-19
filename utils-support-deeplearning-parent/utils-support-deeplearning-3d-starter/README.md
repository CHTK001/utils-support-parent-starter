# utils-support-deeplearning-3d-starter

> utils 深度学习体系中的 **3D 模型生成 starter**：基于 HTTP API 完成文本生 3D、图片生 3D（单图 / 多视角）、草图生 3D 与模型风格化，通过 Spring Boot 自动装配暴露为可直接注入的生成器 Bean。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven-compiler-plugin` 配置 `<release>25</release>` + `--enable-preview`）｜包名：`com.chua.deeplearning.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 规模](#13-规模)
  - [1.4 常用能力速查](#14-常用能力速查)
- [2. 核心 API](#2-核心-api)
  - [2.1 生成器接口](#21-生成器接口)
  - [2.2 数据模型](#22-数据模型)
  - [2.3 请求约束与请求体](#23-请求约束与请求体)
  - [2.4 HTTP 调用与响应解析](#24-http-调用与响应解析)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

本模块是 `utils-support-deeplearning-parent` 下的 3D 生成 starter，只做一件事：把「文本 / 图片 / 多视角图片 / 草图 → 3D 模型」以及「模型风格化」统一成 Java 接口，并用 OkHttp 调用远端 3D 生成 HTTP API，再以 Spring Boot 自动装配的方式把生成器交给宿主使用。

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-3d-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

父模块：`com.chua:utils-support-deeplearning-parent:4.0.0.51`。全部公开类的 `@since` 均为 `4.0.0.42`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 能力 | 文生 3D、图生 3D（单图 / 多视角）、草图生 3D、3D 模型风格化的统一 Java 接口 | 不做本地 3D 推理，不内置任何模型权重 |
| 实现 | 基于 OkHttp 的 HTTP API 客户端实现（`ForgeStyleApiModel3DGenerator`） | 不含网格处理、渲染、预览、格式互转等几何处理能力 |
| 装配 | Spring Boot `@AutoConfiguration` 自动注册 5 个生成器 Bean，支持按配置项逐个开关 | 不提供任何 REST Controller / Web 端点 |
| 结果 | 返回内存中的 `Model3D`（模型字节 + 可选纹理），由调用方自行落盘 | 不负责模型文件的持久化、上传或存储管理 |

### 1.3 规模

- Java 源文件 **13** 个，分布在 4 个包：
  - `com.chua.deeplearning.support.core.api`（6 个）：5 个 API 接口 + 配置类
  - `com.chua.deeplearning.support.core.model`（4 个）：`Model3D` 数据模型与两个枚举
  - `com.chua.deeplearning.support.core.provider`（2 个）：抽象基类与 HTTP 实现
  - `com.chua.deeplearning.support.spring.config`（1 个）：自动配置
- 资源文件仅 1 个：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 文本生成 3D | `TextTo3DGenerator.generate(prompt, format, style, quality)` | prompt 英文效果最佳 |
| 单张图片生成 3D | `ImageTo3DGenerator.generate(byte[] image, format, style, quality)` | PNG/JPG/webp，最多 8MB |
| 多视角图片生成 3D | `ImageTo3DGenerator.generate(byte[][] images, format, style, quality)` | 最多 6 张 |
| 草图生成 3D | `SketchTo3DGenerator.generate(sketch, description, format, style, quality)` | 产出无纹理几何体，description 必填 |
| 模型风格化 | `Model3DStylizer.stylize(model, style, resolution)` | 7 种内置风格 |
| 五合一通用入口 | `Model3DGenerator` | 上述全部能力收敛在一个接口 |
| 读取生成结果 | `Model3D.getData()` / `Model3D.writeTo(OutputStream)` | 输入流读取，或直接写入输出流 |
| 开关与调参 | `chua.deeplearning.core.*` 配置项 | 见 [4. 配置项](#4-配置项) |

---

## 2. 核心 API

### 2.1 生成器接口

5 个接口位于 `com.chua.deeplearning.support.core.api`，均 `throws IOException`：

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `Model3DGenerator` | `generate(String prompt, Model3DFormat, Model3DStyle, String quality)` | 文本生成 3D |
| `Model3DGenerator` | `generate(byte[] image, Model3DFormat, Model3DStyle, String quality)` | 单图生成 3D |
| `Model3DGenerator` | `generate(byte[][] images, Model3DFormat, Model3DStyle, String quality)` | 多视角生成 3D |
| `Model3DGenerator` | `generateFromSketch(byte[] sketch, String description, Model3DFormat, Model3DStyle, String quality)` | 草图生成 3D |
| `Model3DGenerator` | `stylize(Model3D model, Model3DStyle, int resolution)` | 风格化处理 |
| `TextTo3DGenerator` | `generate(String prompt, Model3DFormat, Model3DStyle, String quality)` | 文生 3D 专用视图 |
| `ImageTo3DGenerator` | `generate(byte[] image, ...)` / `generate(byte[][] images, ...)` | 图生 3D 专用视图 |
| `SketchTo3DGenerator` | `generate(byte[] sketch, String description, ...)` | 草图生 3D 专用视图 |
| `Model3DStylizer` | `stylize(Model3D model, Model3DStyle, int resolution)` | 风格化专用视图 |

参数口径（源自接口 Javadoc）：

- `prompt`：文本描述，**英文效果最佳**。
- `image`：图片字节，PNG/JPG/webp，**最多 8MB**。
- `images`：多视角图片列表，**最多 6 张**。
- `sketch`：草图字节，PNG/JPG/webp；`description` 为草图描述，**必填**；产出为**无纹理几何体**。
- `quality`：质量，取值 `draft` / `standard` / `high`（Javadoc 原文「draft / 标准 / high」）；传 `null` 时回落到配置项 `defaultQuality`（默认 `standard`）。
- `style`：见下方 `Model3DStyle` 枚举；传 `null` 时行为由实现决定，建议显式传 `Model3DStyle.ORIGINAL`。
- `resolution`：风格化分辨率（`int`）。

### 2.2 数据模型

`com.chua.deeplearning.support.core.model` 包：

| 类型 | 形态 | 内容 |
| --- | --- | --- |
| `Model3D` | 接口 | `getName()/setName(String)`、`getFormat()/setFormat(Model3DFormat)`、`getData()`（返回 `InputStream`）、`setData(byte[])`、`getSize()`（字节数）、`getTexture()/setTexture(byte[])`（纹理图片数据，可能为空）、`writeTo(OutputStream)` |
| `DefaultModel3D` | 默认实现 | 构造器 `new DefaultModel3D(String name, Model3DFormat format, byte[] data)`；`getData()` 对空数据返回空流 |
| `Model3DFormat` | 枚举 | `GLB("glb", "model/gltf-binary")`、`GLTF("gltf", "model/gltf+json")`、`OBJ("obj", "model/obj")`、`FBX("fbx", "model/fbx")`、`PLY("ply", "model/ply")`、`STL("stl", "model/stl")`；方法 `getExtension()`、`getMimeType()` |
| `Model3DStyle` | 枚举 | `ORIGINAL("original", "原样")`、`LOW_POLY("lowpoly", "低多边形")`、`VOXEL("voxel", "体素")`、`BRICKS("bricks", "砖块")`、`LATTICE("lattice", "开放网格")`、`CARTOON("cartoon", "卡通")`、`PHOTOREALISTIC("photorealistic", "写实")`；方法 `getCode()`、`getDescription()` |

### 2.3 请求约束与请求体

唯一具体实现 `ForgeStyleApiModel3DGenerator`（位于 `core.provider`）按 JSON 方式构建请求体，字段如下：

| 请求类型 | JSON 字段 |
| --- | --- |
| 文生 3D | `prompt`、`format`（扩展名）、`style`（风格 code）、`quality` |
| 单图生 3D | `image`（Base64）、`format`、`style`、`quality`、`mime_type`（固定 `image/png`） |
| 多视角生 3D | `format`、`style`、`quality`、`multi_view`（固定 `true`）、`view_1` … `view_N`（各张 Base64） |
| 草图生 3D | `sketch`（Base64）、`description`、`format`、`style`、`quality`、`mime_type`（固定 `image/png`） |
| 风格化 | `model`（原模型字节 Base64）、`style`、`resolution` |

### 2.4 HTTP 调用与响应解析

抽象基类 `AbstractApiModel3DGenerator` 统一完成调用（`doPost`）：

- 请求地址：`{endpoint}/generate`（`endpoint` 来自配置，默认 `https://api.trellis.ai/v1`）。
- 鉴权：`apiKey` 非空时追加请求头 `Authorization: Bearer <apiKey>`。
- 超时：按配置的 `connectTimeout` / `readTimeout` / `writeTimeout` 构建 `OkHttpClient`。
- 响应：`Content-Type` 含 `application/json` 时，解析 JSON 中的 `data` 或 `result` 字段并做 Base64 解码；否则把响应体字节原样作为模型数据。
- 非 2xx 响应或空响应体抛出 `IOException`。

扩展新协议时只需实现 5 个 `buildXxxRequestBody` 抽象方法（见 [5. SPI 扩展点](#5-spi-扩展点)）。

---

## 3. 快速开始

**1）引入依赖**（坐标见 [1.1](#11-maven-坐标)），宿主为 Spring Boot 应用时无需任何额外配置类。

**2）最小配置**（`application.yml`）：

```yaml
chua:
  deeplearning:
    core:
      enable: true                     # 总开关，默认 true
      api-key: your-api-key            # 服务方 API 密钥
      endpoint: https://api.trellis.ai/v1
      default-quality: standard        # quality 传 null 时的兜底值
      read-timeout: 5m                 # 生成耗时较长，读取超时默认 5 分钟
```

**3）从文本生成一个 GLB 模型并落盘**（最短可运行示例）：

```java
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext context = SpringApplication.run(DemoApplication.class, args);

        // 自动装配的 Bean，等价实现为 ForgeStyleApiModel3DGenerator
        TextTo3DGenerator textTo3D = context.getBean(TextTo3DGenerator.class);

        Model3D model = textTo3D.generate(
                "a low poly castle on a hill",
                Model3DFormat.GLB,
                Model3DStyle.LOW_POLY,
                "standard");

        try (OutputStream out = Files.newOutputStream(Path.of("castle.glb"))) {
            model.writeTo(out);   // 也可用 model.getData() 拿 InputStream
        }
    }
}
```

**4）不经过 Spring，直接构造**：

```java
Model3DConfig config = new Model3DConfig();
config.setApiKey("your-api-key");

ForgeStyleApiModel3DGenerator generator = new ForgeStyleApiModel3DGenerator(config);
Model3D model = generator.generate("a wooden barrel", Model3DFormat.OBJ, Model3DStyle.ORIGINAL, "draft");
```

**5）典型组合**：先单图生成，再风格化：

```java
ImageTo3DGenerator imageTo3D = context.getBean(ImageTo3DGenerator.class);
Model3DStylizer stylizer = context.getBean(Model3DStylizer.class);

Model3D rough = imageTo3D.generate(imageBytes, Model3DFormat.GLB, Model3DStyle.ORIGINAL, "standard");
Model3D pretty = stylizer.stylize(rough, Model3DStyle.PHOTOREALISTIC, 512);
```

---

## 4. 配置项

配置类 `Model3DConfig`（`@ConfigurationProperties(prefix = "chua.deeplearning.core")`）：

| 配置键（前缀 `chua.deeplearning.core.`） | 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `enable` | `enable` | `boolean` | `true` | 是否启用 3D 生成（自动装配总开关） |
| `endpoint` | `endpoint` | `String` | `https://api.trellis.ai/v1` | API 端点 URL，实际请求 `{endpoint}/generate` |
| `api-key` | `apiKey` | `String` | 空字符串 | API 密钥（NVIDIA/HF/Replicate），非空时以 `Bearer` 头发送 |
| `connect-timeout` | `connectTimeout` | `Duration` | `30s` | 连接超时时间 |
| `read-timeout` | `readTimeout` | `Duration` | `5m` | 读取超时时间 |
| `write-timeout` | `writeTimeout` | `Duration` | `30s` | 写入超时时间 |
| `default-quality` | `defaultQuality` | `String` | `standard` | 默认质量，`quality` 传 `null` 时兜底 |
| `default-style` | `defaultStyle` | `String` | `original` | 默认风格（当前实现未读取该字段） |
| `debug-log` | `debugLog` | `boolean` | `false` | 是否启用调试日志（当前实现未读取该字段） |
| `max-concurrent` | `maxConcurrent` | `int` | `5` | 最大并发请求数（当前实现未读取该字段） |

自动装配的 5 个 Bean 各有独立开关（`@ConditionalOnProperty`，未配置时默认开启）：

| Bean（注册类型） | 开关键 | 默认 |
| --- | --- | --- |
| `textTo3DGenerator`（`TextTo3DGenerator`） | `chua.deeplearning.core.text-to-3d-enable` | 开启 |
| `imageTo3DGenerator`（`ImageTo3DGenerator`） | `chua.deeplearning.core.image-to-3d-enable` | 开启 |
| `sketchTo3DGenerator`（`SketchTo3DGenerator`） | `chua.deeplearning.core.sketch-to-3d-enable` | 开启 |
| `model3DGenerator`（`Model3DGenerator`） | `chua.deeplearning.core.generator-enable` | 开启 |
| `model3DStylizer`（`Model3DStylizer`） | `chua.deeplearning.core.stylizer-enable` | 开启 |

总开关 `chua.deeplearning.core.enable=false` 时整个自动配置不生效，5 个 Bean 均不注册。

---

## 5. SPI 扩展点

**自动装配入口**：`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册了唯一自动配置类 `com.chua.deeplearning.support.spring.config.GenerationApiModel3DGenerator`。其生效条件：

- classpath 存在 `Model3DGenerator` 与 `ForgeStyleApiModel3DGenerator`（`@ConditionalOnClass`）；
- `chua.deeplearning.core.enable` 未配置或为 `true`；
- 通过 `@EnableConfigurationProperties(Model3DConfig.class)` 绑定配置。

**生成器扩展点**：抽象基类 `AbstractApiModel3DGenerator` 已实现全部 5 个接口及 HTTP 调用、鉴权、响应解析（`doPost` / `parseResultFromJson`），子类只需实现 5 个请求体构建方法：

| 抽象方法 | 对应能力 |
| --- | --- |
| `buildTextRequestBody(prompt, format, style, quality)` | 文生 3D |
| `buildImageRequestBody(image, format, style, quality)` | 单图生 3D |
| `buildMultiViewRequestBody(images, format, style, quality)` | 多视角生 3D |
| `buildSketchRequestBody(sketch, description, format, style, quality)` | 草图生 3D |
| `buildStylizeRequestBody(model, style, resolution)` | 风格化 |

对接新的 3D 服务时：继承 `AbstractApiModel3DGenerator` → 覆写上述方法 → 在自己的 `@AutoConfiguration` 中注册 Bean（本模块未提供 `META-INF/extensions` 声明式 SPI 文件，`src/main/resources` 下仅有上述 AutoConfiguration.imports）。

---

## 6. 构建与依赖

在 `utils-support-parent-starter` 仓库内编译本模块：

```bash
mvn -o -q compile -DskipTests
```

| 依赖 | 版本 | 作用 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 POM | utils 契约与工具层 |
| `com.chua:utils-support-deeplearning-starter` | `${project.version}` | 深度学习基础模块 |
| `org.springframework.boot:spring-boot-autoconfigure` | 随父 POM | `@AutoConfiguration` / `@ConditionalOnProperty` / `@ConfigurationProperties` |
| `com.squareup.okhttp3:okhttp` | `4.12.0` | HTTP 客户端 |
| `com.fasterxml.jackson.core:jackson-databind` | 随父 POM | 请求/响应 JSON |
| `org.projectlombok:lombok` | provided | `@Data`、`@Slf4j` |

---

## 7. 边界：这些能力不在本模块

| 能力 | 去处 |
| --- | --- |
| 本地 3D 推理、网格简化 / 布尔 / UV / 渲染 / 预览、模型格式互转 | 本模块均不提供，源码未提供相关实现 |
| 模型文件持久化、对象存储、上传下载 | 调用方自行处理；可配合仓内文件 / 存储类 starter |
| AI 对话、文生图、语音等其它生成能力 | 见同仓 `utils-support-deeplearning-starter` 及其它 deeplearning starter |
| HTTP 客户端通用能力（重试、代理、连接池定制） | 本模块仅按 `Model3DConfig` 的三个超时构建 `OkHttpClient`，通用 HTTP 能力见 `utils-support-common-starter` |
| 业务侧模型资产管理（渠道、配额、计费等） | 属于上层业务模块，本模块只提供生成调用 |
