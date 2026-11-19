# utils-support-gson-starter

> utils 体系 JSON SPI 的 **Gson 实现**：把 `utils-support-common-starter` 定义的 `JsonProvider` 契约落到 Google Gson 上，统一适配 `@JsonName` / `@JsonIgnore` 门户注解，并由 `@AutoSpi` 在编译期自动生成 SPI 索引，可经 `Json` 门面全局切换。
>
> 版本：`4.0.0.51`（类均 `@since 4.0.0.42`）｜JDK：`25`（`maven.compiler.release=25`，全仓开启预览特性编译）｜包名：`com.chua.gson.support.json`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 组成与规模](#13-组成与规模)
  - [1.4 常用能力速查](#14-常用能力速查)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 序列化](#21-序列化)
  - [2.2 反序列化](#22-反序列化)
  - [2.3 节点工厂与解析](#23-节点工厂与解析)
  - [2.4 行为约定](#24-行为约定)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

模块自述（取自 pom）：**基于 Gson 的 JsonProvider 实现模块：统一门户注解适配、SPI 自动注册**。

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-gson-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 契约实现 | `com.chua.common.support.lang.json.JsonProvider` 的 Gson 实现，方法与 common-starter 契约一一对应 | 不定义新的 JSON 契约（接口、`Json` 门面、节点基类都在 common-starter） |
| 注解适配 | 通过 Gson 的 `FieldNamingStrategy` / `ExclusionStrategy` 识别 common-starter 的 `@JsonName` / `@JsonIgnore` | 不要求业务代码使用 Gson 专属注解（如 `@SerializedName`） |
| 注册方式 | `@Spi("gson")` + `@AutoSpi` 编译期生成 `META-INF/extensions` 索引 | 不是 Spring Boot starter：无自动配置、无配置项 |
| 节点工厂 | 返回本模块的 `GsonJsonObject` / `GsonJsonArray` / `GsonJsonNode` | 不提供 JSON Path、JSON5、模板抽取等上层能力 |
| 运行环境 | 自带 GraalVM `native-image` 的 reflect / proxy / resource 配置 | 不负责 AOT 编译本身 |

### 1.3 组成与规模

单包 `com.chua.gson.support.json`，共 4 个源文件：

| 类型 | 职责 |
| --- | --- |
| `GsonJsonProvider` | `JsonProvider` 实现：序列化、反序列化、节点工厂、校验；内含 `JsonNameFieldNamingStrategy`、`JsonIgnoreExclusionStrategy` 两个静态内部类 |
| `GsonJsonObject` | `JsonObject` 节点子类，由 `createJsonObject()` 工厂返回 |
| `GsonJsonArray` | `JsonArray` 节点子类，由 `createJsonArray()` 工厂返回 |
| `GsonJsonNode` | `JsonNode` 节点子类，由 `createJsonNode(Object)` 工厂返回 |

资源文件：SPI 索引 1 个（`META-INF/extensions/com.chua.common.support.lang.json.JsonProvider`）+ GraalVM native-image 配置 3 个。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 对象 → JSON | `GsonJsonProvider#toJson(Object)` / `toJson(Object, String... ignores)` | `ignores` 剔除顶层字段 |
| JSON → 对象 | `GsonJsonProvider#fromJson(String, Class)` / `fromJson(byte[], Class)` / `fromJson(Reader, Class)` | 失败抛 `RuntimeException` |
| JSON → 列表 | `GsonJsonProvider#fromJsonToList(String, Class)` / `fromJsonToList(InputStream, Class)` | 泛型列表反序列化 |
| JSON → Map | `GsonJsonProvider#fromJson(String)` | 返回 `Map<String, Object>` |
| 解析为节点 | `GsonJsonProvider#parse(String)` / `parse(byte[])` | 返回 `JsonNode`；null/非法 JSON 返回空对象节点，不抛错 |
| 美化输出 | `GsonJsonProvider#toPrettyJson(Object)` / `prettyFormat(Object)` | 内部独立 pretty 实例 |
| 字节数组 | `GsonJsonProvider#toJsonByte(Object)` / `toJSONBytes(Object)` | UTF-8 编码 |
| 合法性 | `GsonJsonProvider#validate(String)` / `isJson(Object)` | `validate` 解析失败返回 `false`；`isJson` 仅按 `{}`/`[]` 首尾判断 |

---

## 2. 核心 API 与速查表

以下方法均为 `GsonJsonProvider` 实现 `JsonProvider` 契约的方法，与源码一一对应。

### 2.1 序列化

| 方法 | 说明 |
| --- | --- |
| `String toJson(Object object, String... ignores)` | 无 ignores 时直接 `gson.toJson`；有 ignores 时先 `toJsonTree` 转树再逐个 `remove`（仅顶层对象字段生效） |
| `String prettyFormat(Object object)` | 美化输出（独立 `setPrettyPrinting` 实例） |
| `String toPrettyJson(Object obj)` | 等价于 `prettyFormat` |
| `byte[] toJsonByte(Object object)` | JSON 字符串按 UTF-8 编码 |
| `byte[] toJSONBytes(Object object)` | 等价于 `toJsonByte` |
| `String toJSONString(Object object)` | 等价于 `toJson(object)` |
| `void toJson(Object object, Writer writer)` | 写入字符流 |

### 2.2 反序列化

| 方法 | 说明 |
| --- | --- |
| `<T> T fromJson(String json, Class<T> target)` | JSON → 指定类型；失败抛 `RuntimeException("JSON 处理失败: …")` |
| `<T> T fromJson(byte[] bytes, Class<T> target)` | 字节数组按 UTF-8 解码后同上 |
| `<T> T fromJson(Reader reader, Class<T> target)` | 字符流 |
| `<T> T fromJson(InputStreamReader inputStreamReader, Class<T> target)` | 字符流 |
| `<T> T fromJson(InputStream inputStream, Class<T> target)` | 字节流，内部按 UTF-8 读取 |
| `<T> T fromJson(String stringValue, Type type)` | 泛型/复杂类型 |
| `<T> T fromJson(Reader reader, Type type)` / `fromJson(InputStream stream, Type type)` | 流式 + 泛型类型 |
| `Map<String, Object> fromJson(String string)` | 解析为 `Map<String, Object>`（`TypeToken`） |
| `<T> List<T> fromJsonToList(String json, Class<T> targetType)` | JSON 数组 → `List<T>`（`TypeToken.getParameterized`）；入参 `null` 返回空列表 |
| `<T> List<T> fromJsonToList(InputStream inputStream, Class<T> targetType)` | 字节流按 UTF-8 读取后同上 |
| `JsonObject fromJson(byte[] bytes, Charset charset)` | 按指定字符集解码为 `JsonObject` |
| `List<?> toList(String string)` | 解析为 `List`，失败抛 `RuntimeException` |

### 2.3 节点工厂与解析

| 方法 | 说明 |
| --- | --- |
| `JsonObject createJsonObject()` / `createJsonObject(Map map)` | 创建 `GsonJsonObject`（可带初始数据） |
| `JsonArray createJsonArray()` / `createJsonArray(Collection collection)` | 创建 `GsonJsonArray` |
| `JsonNode createJsonNode(Object value)` | 创建 `GsonJsonNode` |
| `JsonNode parse(String json)` | 解析为节点；`null` 或 `JsonSyntaxException` 时返回空对象节点（宽松） |
| `JsonNode parse(byte[] json)` | 字节数组按 UTF-8 后同上 |
| `JsonNode build()` / `buildArray()` | 空对象节点 / 空数组节点 |
| `JsonObject getJsonObject(String json)` | 解析为 `GsonJsonObject`；`null`/非法 JSON 返回空对象（宽松） |
| `JsonObject getJsonObject(byte[] bytes)` | 注意：失败**抛** `RuntimeException("JSON 处理失败: …")`（与 `getJsonObject(String)` 行为不同） |
| `JsonObject getJsonObject(InputStreamReader inputStreamReader)` | 字符流；失败抛 `RuntimeException` |
| `JsonObject getJsonObject(InputStream inputStream)` | 按 UTF-8 |
| `JsonObject getJsonObject(InputStream inputStream, String charset)` | 指定字符集；编码不支持时抛 `RuntimeException` |
| `JsonArray getJsonArray(String json)` / `getJsonArray(byte[] jsonArray)` | 解析为数组；`null`/非法返回空数组（宽松） |
| `JsonReference getJsonReference(String json)` | 返回 `new JsonReference(json)` |
| `boolean isJson(Object ext)` | 仅当 `String` 去空白后以 `{}` 或 `[]` 首尾匹配返回 `true` |
| `boolean validate(String jsonStr)` | 尝试解析，`JsonSyntaxException` 返回 `false`，不抛错 |

### 2.4 行为约定

- **双实例**：构造时创建常规 `gson` 与美化输出 `prettyGson` 两个实例；Gson 配置后不可变、线程安全（源码注释），`GsonJsonProvider` 可作单例复用。
- **统一门户注解适配**（本模块的核心价值）：
  - `@JsonName`（`com.chua.common.support.lang.json.annotation.JsonName`）→ `JsonNameFieldNamingStrategy` 映射为 Gson 字段名；
  - `@JsonIgnore` → `JsonIgnoreExclusionStrategy` 在**序列化与反序列化两个方向**排除字段；
  - 业务代码因此不需要 Gson 专属注解。类注释另提及 `@JsonFormat`，但源码仅实现了前两者的适配。
- **java.time 支持**：Gson 默认不支持 `java.time`，本模块显式注册三个 `TypeAdapter`：
  - `LocalDateTime` ↔ `yyyy-MM-dd HH:mm:ss`
  - `LocalDate` ↔ `yyyy-MM-dd`
  - `LocalTime` ↔ `HH:mm:ss`
  - 另设 `setDateFormat("yyyy-MM-dd HH:mm:ss")`（影响 `java.util.Date`）。空字符串反序列化为 `null`。
- **宽松与严格两套语义**：`parse` / `getJsonObject(String)` / `getJsonArray` 对 `null` 与非法 JSON 返回空容器（不抛错）；`fromJson(...)`、`fromJsonToList`、`toList`、`getJsonObject(byte[]/流)` 失败抛 `RuntimeException("JSON 处理失败: …")`。按需选择。
- **字符集**：所有 `byte[]` 路径固定 UTF-8；仅 `fromJson(byte[], Charset)` 与 `getJsonObject(InputStream, String charset)` 可指定字符集。

---

## 3. 快速开始

引入依赖后（坐标见 [1.1](#11-maven-坐标)），有两种用法。

**用法一：直接实例化，不影响全局默认实现**

```java
import com.chua.gson.support.json.GsonJsonProvider;

GsonJsonProvider json = new GsonJsonProvider();

String s = json.toJson(java.util.Map.of("name", "张三"));   // {"name":"张三"}
java.util.Map<String, Object> back = json.fromJson(s, java.util.Map.class);
```

**用法二：经 `Json` 门面全局切换，享受统一门户注解适配**

```java
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.annotation.JsonIgnore;
import com.chua.common.support.lang.json.annotation.JsonName;
import com.chua.gson.support.json.GsonJsonProvider;

public class QuickStart {

    static class User {
        @JsonName("user_name")
        private String name;
        private java.time.LocalDate birthday;   // 自动按 yyyy-MM-dd 序列化
        @JsonIgnore
        private String password;                // 序列化/反序列化均忽略

        String getName() { return name; }
        void setName(String name) { this.name = name; }
        java.time.LocalDate getBirthday() { return birthday; }
        void setBirthday(java.time.LocalDate birthday) { this.birthday = birthday; }
        String getPassword() { return password; }
        void setPassword(String password) { this.password = password; }
    }

    public static void main(String[] args) {
        // 切换全局 JSON 实现（不切换时门面默认使用 JacksonJsonProvider）
        Json.setImplementation(new GsonJsonProvider());

        User user = new User();
        user.setName("张三");
        user.setBirthday(java.time.LocalDate.of(2026, 1, 1));
        user.setPassword("secret");

        String json = Json.toJson(user);
        // {"user_name":"张三","birthday":"2026-01-01"}（password 被忽略）

        User back = Json.fromJson(json, User.class);
    }
}
```

也可以通过 SPI 按名获取：`ServiceProvider.of(JsonProvider.class).getExtension("gson")`。

---

## 4. 配置项

**无。**

依据：`src/main/resources` 下只有 SPI 索引文件与 3 个 native-image 配置，没有任何 `application*.properties` / `*.yml`；源码中没有 `@ConfigurationProperties`、系统属性或环境变量读取。日期格式、注解适配策略均在 `GsonJsonProvider` 构造函数中写死，也没有公开的 Builder 或 setter 供定制；如需定制行为，请在 common-starter 层面另行实现 `JsonProvider` 契约。

---

## 5. SPI 扩展点

本模块是 **SPI 实现方**，不定义新的 SPI 扩展点（`META-INF/extensions/` 下仅 `JsonProvider` 一个索引文件）。

| 项 | 内容 |
| --- | --- |
| 实现的契约 | `com.chua.common.support.lang.json.JsonProvider`（定义于 utils-support-common-starter） |
| 实现类 | `com.chua.gson.support.json.GsonJsonProvider` |
| SPI 名称 | `gson`（类上标注 `@Spi("gson")`） |
| 索引文件 | `META-INF/extensions/com.chua.common.support.lang.json.JsonProvider`，内容：`gson=com.chua.gson.support.json.GsonJsonProvider` |
| 索引生成 | 由 `@AutoSpi("com.chua.common.support.lang.json.JsonProvider")` 配合 `utils-support-ast-starter` 的 `AutoSpiAstProcessor` 在编译期自动生成（pom 注释注明） |
| 按名获取 | `ServiceProvider.of(JsonProvider.class).getExtension("gson")` |
| 全局切换 | `Json.setImplementation(new GsonJsonProvider())`；门面默认经 SPI 发现标记 `@SpiDefault` 的 `JacksonJsonProvider`，发现失败回退 `new JacksonJsonProvider()` |

GraalVM native-image 说明（`src/main/resources/META-INF/native-image/com.chua/utils-support-gson-starter/`）：

- `reflect-config.json`：注册本模块 4 个类与 2 个内部策略类、common-starter 的 `Json` 门面与节点类型、Gson 的 `Gson` / `GsonBuilder` / `TypeAdapter` / `FieldNamingStrategy` / `ExclusionStrategy` / `TypeToken`；
- `proxy-config.json`：注册 `JsonProvider` 接口代理；
- `resource-config.json`：包含 `META-INF/services/*` 与 `META-INF/extensions/*`。

---

## 6. 构建与依赖

依赖（取自本模块 `pom.xml`）：

| 依赖 | 范围 | 版本 | 用途 |
| --- | --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | `${project.version}`（4.0.0.51） | `JsonProvider` 契约、`@JsonName` / `@JsonIgnore` 注解、工具类 |
| `com.google.code.gson:gson` | compile | `2.15.0`（父 pom dependencyManagement 统一管理） | JSON 引擎 |
| `com.chua:utils-support-ast-starter` | provided | `${project.version}` | `@AutoSpi` 注解 + `AutoSpiAstProcessor` 编译期生成 SPI 索引 |
| `org.projectlombok:lombok` | annotationProcessorPaths | `${lombok.version}`（1.18.48） | 编译期注解处理 |

编译说明：父级 `pluginManagement` 只声明 lombok 处理器、会禁用 classpath 处理器发现，因此本模块在 `maven-compiler-plugin`（3.16.0，`release=25`）中显式叠加了 `annotationProcessorPaths`（lombok + utils-support-ast-starter）。

```bash
# 全仓编译（工作区交付闸门口径）
mvn -o -q compile -DskipTests

# 只编译本模块及其依赖（在 utils-support-parent-starter 仓库根目录）
mvn -o -q -pl utils-support-extra-parent/utils-support-gson-starter -am compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `Json` 门面、`JsonProvider` 契约、`JsonObject` / `JsonArray` / `JsonNode` / `JsonReference` 基类、`@JsonName` / `@JsonIgnore` 注解 | `utils-support-common-starter` |
| 默认 JSON 实现（Jackson） | `utils-support-common-starter` 的 `JacksonJsonProvider` |
| JSON Path、JSON5、JSON 模板抽取 | `utils-support-common-starter`（`lang.json` 包） |
| 其它序列化方案（fastjson、fory、protobuf 等） | 同父模块下的 `utils-support-fastjson-starter`、`utils-support-fory-starter`、`utils-support-protobuf-starter` 等模块，各自职责见其文档 |
| Spring Boot 自动配置、配置项绑定 | 无：本模块不是 Spring Boot starter，也不读取任何配置 |
| Gson 定制入口（自定义 `GsonBuilder`、注册额外 TypeAdapter） | 未提供：内部 `Gson` 实例在构造函数中固化，无公开定制点 |
| 日期格式的可配置化 | 未提供：`LocalDateTime` / `LocalDate` / `LocalTime` 的格式在构造时固定为上述三种模式 |
