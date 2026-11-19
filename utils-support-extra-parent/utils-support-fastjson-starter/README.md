# utils-support-fastjson-starter

> utils 体系 JSON SPI 的 **Fastjson 实现**：把 common-starter 的 `JsonProvider` 契约落到 `com.alibaba:fastjson` 上，并提供统一门户注解（`@JsonName` / `@JsonIgnore` / `@JsonFormat`）的桥接适配；SPI 索引由 `@AutoSpi` 在编译期自动生成。
>
> 版本：`4.0.0.51`｜JDK：`25`（根父 pom `java.version=25`，`maven.compiler.release=25`）｜包名：`com.chua.fastjson.support.json`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 行为语义](#4-行为语义)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-fastjson-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `JsonProvider` 的 fastjson 实现模块（pom 描述：基于 Fastjson 的 JsonProvider 实现模块：统一门户注解适配、SPI 自动注册） | 不是 JSON 门面（`Json` / `Json5` 门面与契约在 `utils-support-common-starter`） |
| 注解 | 通过 `JsonBeanMapper` 桥接统一门户注解 `@JsonName` / `@JsonIgnore` / `@JsonFormat` | 不定义新的 JSON 注解，也不依赖 fastjson 的 `@JSONField` 注解编程 |
| 注册 | `@Spi("fastjson")` + `@AutoSpi` 编译期生成 `META-INF/extensions` SPI 索引 | 不是 Spring Boot starter：无自动配置类、无配置项 |
| 依赖 | 只依赖 common-starter 与 fastjson / fastjson2 | 不依赖 Spring、不依赖任何上层业务 starter |

### 1.3 规模

- Java 源文件 **4** 个，均在唯一包 `com.chua.fastjson.support.json`。
- SPI 索引 **1** 个：`src/main/resources/META-INF/extensions/com.chua.common.support.lang.json.JsonProvider`。
- GraalVM `native-image` 配置 **3** 个：`src/main/resources/META-INF/native-image/com.chua/utils-support-fastjson-starter/` 下的 `reflect-config.json`、`proxy-config.json`、`resource-config.json`。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 全局切换 JSON 实现 | `Json.setImplementation(new FastjsonJsonProvider())` | 不切换时默认实现是 `JacksonJsonProvider` |
| 按名字取实现 | `ServiceProvider.of(JsonProvider.class).getExtension("fastjson")` | SPI 名称为 `fastjson` |
| 对象 → JSON | `provider.toJson(obj)` / `Json.toJson(obj)` | 恒定启用日期格式化；可传 `ignores` 剔除字段 |
| Pretty 输出 | `provider.prettyFormat(obj)` / `provider.toPrettyJson(obj)` | 带缩进的 JSON 字符串 |
| JSON → 对象 | `provider.fromJson(json, User.class)` / `Json.fromJson(json, User.class)` | 支持 `String` / `byte[]` / `Reader` / `InputStream` / `Type` |
| JSON → 列表 | `provider.fromJsonToList(json, User.class)` | 入参为 `null` 时返回空列表 |
| 解析为节点 | `provider.parse(json)` / `provider.getJsonObject(json)` / `provider.getJsonArray(json)` | 节点类型随当前实现切换 |
| 校验 JSON | `provider.validate(jsonStr)` / `provider.isJson(ext)` | `validate` 真实解析；`isJson` 只判断字符串形状 |
| 门户注解适配 | `@JsonName("user_name")` / `@JsonIgnore` / `@JsonFormat("yyyy-MM-dd HH:mm:ss")` | 由 `JsonBeanMapper` 桥接，各实现行为一致 |

---

## 2. 核心 API 与速查表

### 2.1 FastjsonJsonProvider

`com.chua.fastjson.support.json.FastjsonJsonProvider` 实现 common-starter 的 `JsonProvider` 接口全部方法；所有方法的名称、签名与 common-starter 的 `Json` 静态门面一一对应。

**创建节点**

| 方法 | 说明 |
| --- | --- |
| `createJsonObject()` / `createJsonObject(Map map)` | 创建 `FastjsonJsonObject` |
| `createJsonArray()` / `createJsonArray(Collection collection)` | 创建 `FastjsonJsonArray` |
| `createJsonNode(Object value)` | 创建 `FastjsonJsonNode` |
| `build()` / `buildArray()` | 分别等价于 `createJsonNode(createJsonObject())` / `createJsonNode(createJsonArray())` |

**解析**

| 方法 | 说明 |
| --- | --- |
| `parse(String json)` / `parse(byte[] json)` | 以 `[` 开头按数组解析，否则按对象解析；入参为 `null` 或解析失败返回空对象节点 |
| `getJsonObject(String json)` | 解析失败返回空 `JsonObject` |
| `getJsonObject(byte[] bytes)` / `getJsonObject(InputStream)` / `getJsonObject(InputStream, String charset)` / `getJsonObject(InputStreamReader)` | 失败抛 `RuntimeException("JSON 处理失败: ...")`；字节流按 UTF-8 或指定字符集读取 |
| `getJsonArray(String json)` / `getJsonArray(byte[] jsonArray)` | 解析失败返回空 `JsonArray` |
| `getJsonReference(String json)` | 返回 `new JsonReference(json)` |

**反序列化**

| 方法 | 说明 |
| --- | --- |
| `fromJson(String json, Class<T> target)` | 目标类型携带门户注解时走 `JsonBeanMapper` 桥接，否则直接 `JSON.parseObject` |
| `fromJson(String stringValue, Type type)` | 同上（`type instanceof Class` 时检查门户注解） |
| `fromJson(byte[] bytes, Class<T> target)` / `fromJson(Reader, Class<T>)` / `fromJson(InputStream, Class<T>)` / `fromJson(InputStreamReader, Class<T>)` | 字符流统一按 UTF-8 读取后委托字符串版本 |
| `fromJson(Reader reader, Type type)` / `fromJson(InputStream stream, Type type)` | `Type` 版本 |
| `fromJson(byte[] bytes, Charset charset)` | 返回 `JsonObject` |
| `fromJson(String string)` | 返回 `Map<String, Object>` |
| `fromJsonToList(String json, Class<T> targetType)` / `fromJsonToList(InputStream, Class<T>)` | 入参为 `null` 返回空列表 |

**序列化**

| 方法 | 说明 |
| --- | --- |
| `toJson(Object object, String... ignores)` | `null` 返回 `"null"`；恒定启用 `SerializerFeature.WriteDateUseDateFormat`；`ignores` 对 Map 走副本剔除、对 Bean 走 `SimplePropertyPreFilter` |
| `toJson(Object object, Writer writer)` | 写入字符流 |
| `prettyFormat(Object object)` / `toPrettyJson(Object obj)` | 追加 `SerializerFeature.PrettyFormat`；`toPrettyJson` 直接委托 `prettyFormat` |
| `toJsonByte(Object object)` / `toJSONBytes(Object object)` | `null` 返回空字节数组；`toJSONBytes` 委托 `toJsonByte` |
| `toJSONString(Object object)` | 委托 `toJson` |

**判断与工具**

| 方法 | 说明 |
| --- | --- |
| `validate(String jsonStr)` | 真实执行 `JSON.parse`，可解析返回 `true`，异常返回 `false` |
| `isJson(Object ext)` | 仅当入参是 `String` 且 trim 后以 `{}` 或 `[]` 形状包裹时返回 `true` |
| `toList(String string)` | `JSON.parseArray`，失败抛 `RuntimeException("JSON 处理失败: ...")` |

### 2.2 节点类型

| 类 | 继承自 | 构造函数 |
| --- | --- | --- |
| `FastjsonJsonObject` | `JsonObject` | `FastjsonJsonObject()`；`FastjsonJsonObject(Map m)` |
| `FastjsonJsonArray` | `JsonArray` | `FastjsonJsonArray()`；`FastjsonJsonArray(Collection collection)` |
| `FastjsonJsonNode` | `JsonNode` | `FastjsonJsonNode(Object value)` |

三者均由 `FastjsonJsonProvider` 的节点工厂返回，使 `Json.createJsonObject()`、`JsonObject.create()`、`Json.createJsonArray()`、`JsonArray.of()`、`Json.createJsonNode(value)`、`JsonNode.valueOf(value)` 等创建路径的节点类型随当前实现切换（类注释原文语义）。

### 2.3 统一门户注解适配

fastjson 原生只识别自身注解（`@JSONField`），无法原生识别 common-starter 的门户注解。本实现通过 `JsonBeanMapper` 桥接：

| 门户注解 | 作用 |
| --- | --- |
| `@JsonName("user_name")` | 指定序列化 / 反序列化使用的 JSON 字段名（可标注在字段、getter、参数上） |
| `@JsonIgnore` | 序列化 / 反序列化时忽略该字段（可标注在字段、getter 上） |
| `@JsonFormat("yyyy-MM-dd HH:mm:ss")` | 指定日期 / 时间字段格式（支持 `Date`、`LocalDateTime`、`LocalDate`、`LocalTime` 等） |

桥接流程（源码行为）：

- **序列化**：`hasUnifiedAnnotations(type)` 检测到字段携带任一门户注解时，先 `JsonBeanMapper.toMap(object)` 转成普通 Map，再交给 `JSON.toJSONString`。
- **反序列化**：先 `JSON.parseObject(json, Map.class)`，再 `JsonBeanMapper.fromMap(map, target)`。
- 未携带门户注解的类型直接走 fastjson 原生编解码，无额外开销。

```java
public class User {
    @JsonName("user_name")
    private String userName;

    @JsonIgnore
    private String password;

    @JsonFormat("yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    // getter / setter ...
}

Json.setImplementation(new FastjsonJsonProvider());
Json.toJson(user);                 // {"user_name":"...","createTime":"2026-08-15 12:30:00"}，password 不输出
Json.fromJson(json, User.class);   // 字段名、忽略、日期格式均按门户注解还原
```

---

## 3. 快速开始

最短可运行示例（依赖本模块 + common-starter 传递依赖）：

```java
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.fastjson.support.json.FastjsonJsonProvider;

public class QuickStart {

    public static class User {
        private String name;
        private Integer age;

        public User(String name, Integer age) {
            this.name = name;
            this.age = age;
        }

        public String getName() { return name; }
        public Integer getAge() { return age; }
    }

    public static void main(String[] args) {
        // 1. 全局切换 JSON 实现（不切换则默认 Jackson 实现）
        Json.setImplementation(new FastjsonJsonProvider());

        // 2. 对象 -> JSON
        String json = Json.toJson(new User("张三", 25));

        // 3. JSON -> 对象
        User user = Json.fromJson(json, User.class);

        // 4. JSON -> 节点
        JsonObject obj = Json.getJsonObject("{\"a\":1}");

        // 5. 校验
        boolean ok = Json.validate(json);
    }
}
```

也可以不切全局、按 SPI 名字直接取实现单独使用：

```java
import com.chua.common.support.lang.json.JsonProvider;
import com.chua.common.support.spi.ServiceProvider;

JsonProvider provider = ServiceProvider.of(JsonProvider.class).getExtension("fastjson");
String json = provider.toJson(new User("张三", 25));
```

---

## 4. 行为语义

各方法在异常与空值下的行为不同，选用前先对照（均为源码行为）：

| 场景 | 行为 |
| --- | --- |
| `parse(String/byte[])` 解析失败 | 返回空 `JsonObject` 节点（不抛异常） |
| `getJsonObject(String)` / `getJsonArray(String/byte[])` 解析失败 | 返回空容器（不抛异常） |
| `getJsonObject(byte[]/InputStream/InputStreamReader)` 失败 | 抛 `RuntimeException("JSON 处理失败: ...")` |
| `fromJson(...)` / `fromJsonToList(...)` / `toList(String)` 失败 | 抛 `RuntimeException("JSON 处理失败: ...")` |
| `toJson(null)` | 返回字符串 `"null"` |
| `toJsonByte(null)` | 返回空字节数组 |
| `fromJsonToList(null, type)` | 返回空列表 |
| `validate(非法 JSON)` | 返回 `false` |
| 日期序列化 | 恒定 `WriteDateUseDateFormat`；pretty 额外 `PrettyFormat` |

---

## 5. 配置项

无。依据：

- 本模块 `pom.xml` 无任何 Spring / Spring Boot 依赖，也不是 Spring Boot 自动配置模块，不存在 `@ConfigurationProperties` 前缀；
- `src/main/resources` 下只有 SPI 索引与 native-image 配置文件，没有任何 properties / yaml 配置键；
- 源码中无读取系统属性或环境变量的配置代码。`@Spi("fastjson")` 与 `@AutoSpi` 仅用于 SPI 注册，不消费配置。

序列化行为（日期格式、Pretty、ignores）由方法参数与 `SerializerFeature` 决定，不属于外部配置项。

---

## 6. SPI 扩展点

本模块是 `JsonProvider` SPI 的**实现方**，不是新扩展点的提供方。

**注册方式**（两层，缺一不可）：

1. 源码标注：`@Spi("fastjson")`（SPI 名称）+ `@AutoSpi(value = "com.chua.common.support.lang.json.JsonProvider")`；
2. 编译期由 `utils-support-ast-starter` 的 `AutoSpiAstProcessor`（provided 依赖 + `annotationProcessorPaths` 显式启用）生成索引文件：

```text
src/main/resources/META-INF/extensions/com.chua.common.support.lang.json.JsonProvider
fastjson=com.chua.fastjson.support.json.FastjsonJsonProvider
```

**启用本实现的三种方式**：

| 方式 | 代码 |
| --- | --- |
| 运行时全局替换（推荐，影响 `Json` / `Json5` 全部门面） | `Json.setImplementation(new FastjsonJsonProvider())` |
| SPI 按名获取 | `ServiceProvider.of(JsonProvider.class).getExtension("fastjson")` |
| `Json5` 门面透传 | `Json5.setImplementation(provider)`（common-starter 提供，内部透传 `Json.setImplementation`） |

> common-starter 的默认实现是 `JacksonJsonProvider`（`@SpiDefault`）；`Json` 门面在 SPI 发现失败时回退 `new JacksonJsonProvider()`。即：不引入本模块或未切换时，fastjson 路径不生效。

**如何扩展 JSON 能力**：实现 common-starter 的 `JsonProvider` 接口，标注自己的 `@Spi("名称")` 并注册 `META-INF/extensions` 索引即可与本品并列；不要修改本模块来挂接第三方 JSON 库。

---

## 7. 构建与依赖

### 7.1 依赖清单（本模块 pom）

| 依赖 | 范围 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | compile | `JsonProvider` / `Json*` 契约、`Json` 门面、门户注解与 `JsonBeanMapper`、`ServiceProvider` |
| `com.alibaba:fastjson` | compile | 由根父 pom 统一管理为 `2.0.66`（fastjson2 的 1.x 兼容层，提供 `com.alibaba.fastjson.JSON` 等 API） |
| `com.alibaba.fastjson2:fastjson2` | compile | 本模块显式锁定 `2.0.65`：父级 dependencyManagement 管理的是 android 变体 `2.0.65.android8`（缺 `util.Wrapper`），此处覆盖为标准版以保证编译与运行完整（pom 注释原文语义） |
| `com.chua:utils-support-ast-starter` | provided | 仅编译期：`@AutoSpi` 注解 + `AutoSpiAstProcessor` 生成 SPI 索引 |
| `org.projectlombok:lombok` | annotationProcessor | 注解处理器（版本由根父 pom `lombok.version` 管理） |

### 7.2 构建

```bash
# 在 utils-support-parent-starter 目录下
mvn -o -q compile -DskipTests     # 编译（EXIT=0 为通过）
mvn clean install -DskipTests     # 安装到本地仓库供上层引用
```

编译说明（pom 注释原文语义）：父级 pluginManagement 只声明 lombok，会禁用 classpath 处理器发现，因此本模块在 `maven-compiler-plugin` 的 `annotationProcessorPaths` 中显式叠加 `AutoSpiAstProcessor` 与 lombok。

### 7.3 GraalVM native-image

`src/main/resources/META-INF/native-image/com.chua/utils-support-fastjson-starter/` 已随模块提供 `reflect-config.json`、`proxy-config.json`、`resource-config.json`。

---

## 8. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `Json` / `Json5` 静态门面、`JsonProvider` / `JsonObject` / `JsonArray` / `JsonNode` / `JsonReference` 契约 | `utils-support-common-starter`（`com.chua.common.support.lang.json`） |
| 门户注解 `@JsonName` / `@JsonIgnore` / `@JsonFormat` 与 `JsonBeanMapper` | `utils-support-common-starter`（`com.chua.common.support.lang.json.annotation`） |
| 默认 JSON 实现（Jackson，`@SpiDefault`） | `utils-support-common-starter`（`JacksonJsonProvider`，SPI 名 `jackson`） |
| 其它 JSON 实现的切换（Gson、Fory 等） | 对应 `utils-support-gson-starter`、`utils-support-fory-starter` 等模块 |
| JSON 文件读写（按扩展名的 `FileSystem`） | `utils-support-common-starter` 的 `file.JsonFileSystem` 及 filesystem 相关模块 |
| SPI 服务发现机制（`ServiceProvider`、`@Spi`、`@Extension` 等） | `utils-support-common-starter` 的 `spi` 包 |
| Spring 自动配置、配置项、健康检查等 starter 能力 | 本模块不提供（它只是纯 SPI 实现模块） |
