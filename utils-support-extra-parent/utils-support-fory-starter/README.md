# utils-support-fory-starter

> utils 体系的 **Apache Fory（Fury）序列化实现模块**：把 Apache Fory 的高性能二进制序列化与官方 fory-json 接入 `utils-support-common-starter` 的 `Serializer` / `Serialization` / `JsonProvider` 三套 SPI 契约，供 RPC、任务派发、缓存等场景按名字（`fory` / `fury`）装配。
>
> 版本：`4.0.0.51`（随父 `utils-support-extra-parent`；实现类自 `4.0.0.42` 起提供）｜JDK：`25`（继承根 pom，`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.fory.support.serialize`、`com.chua.fory.support.json`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

Maven 坐标：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-fory-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Apache Fory 的 **SPI 适配层**：二进制序列化（`ForySerialization` / `ForySerializer`）+ JSON（`ForyJsonProvider`） | 不定义序列化契约；`Serializer` / `Serialization` / `JsonProvider` 接口都在 `utils-support-common-starter` |
| 引擎 | 基于 `org.apache.fory:fory-core`（Fory 内核）与 `org.apache.fory:fory-json`（Fory 官方 JSON 模块） | 不是自研序列化协议，也不重写 Fory |
| 形态 | utils SPI 体系下的实现模块：通过 `META-INF/extensions` 与 `META-INF/services` 注册，按名字装配 | 不是 Spring Boot starter：不依赖 Spring、没有自动配置类 |
| 安全 | 反序列化经 `DeserializationGuard` 类名守卫（命中黑名单的类名在解析阶段即被拒绝） | 不提供白名单策略本体；策略与守卫实现位于 common-starter |

模块共 6 个类：

| 类 | 实现的契约 | 说明 |
| --- | --- | --- |
| `serialize.ForySerialization` | `com.chua.common.support.base.serialize.Serialization` | 全局共享一个线程安全 Fury，二进制对象图编解码 |
| `serialize.ForySerializer<T>` | `com.chua.common.support.serialize.Serializer<T>` | 按目标类型缓存的线程安全 Fury 门面池 |
| `json.ForyJsonProvider` | `com.chua.common.support.lang.json.JsonProvider` | JSON 解析 / 生成 / 校验 + 门户注解桥接 |
| `json.ForyJsonObject` | `JsonObject` 子类 | 节点类型随当前实现切换 |
| `json.ForyJsonArray` | `JsonArray` 子类 | 同上 |
| `json.ForyJsonNode` | `JsonNode` 子类 | 同上 |

---

## 2. 核心 API 与速查表

### 2.1 二进制序列化

**`ForySerialization`**（实现 `Serialization`，无参构造）：

| 方法 | 行为 |
| --- | --- |
| `name()` | 返回 `"fory"` |
| `serialize(Object obj)` | `null` → 空字节数组；否则 Fury 编码为字节数组 |
| `deserialize(byte[] data, Class<T> type)` | `null` / 空数组 → `null`；Fury Java 模式在数据流中携带类型信息，`type` 仅用于返回类型约束，实际解码不依赖该参数 |

**`ForySerializer<T extends Serializable>`**（实现 `Serializer<T>`，被序列化类型需实现 `Serializable`）：

| 成员 | 行为 |
| --- | --- |
| `new ForySerializer(Class<T> clazz)` | 按目标类型创建；每个类型对应一个 `ThreadSafeFory`（`ConcurrentHashMap` 缓存，创建时打印 `[ForyPool] Created Fury instance #N for 类名`） |
| `serialize(T object)` | `null` → 空字节数组 |
| `deserialize(byte[] bytes)` | `null` / 空数组 → `null` |
| `clearPool()`（静态） | 清空全部 Fury 实例缓存，打印 `[ForyPool] All Fury instances cleared`；适用于测试或释放资源 |
| `getPoolSize()`（静态） | 当前缓存的 Fury 实例数量 |

> `Fory` 本身**非线程安全**：两个类持有的都是 `ThreadSafeFory`（内部为每个线程维护独立编解码器），调用方无需再加锁。

### 2.2 JSON（`ForyJsonProvider`）

底层为 `org.apache.fory.json.ForyJson`（`ForyJson.builder().build()`，线程安全、配置后不可变），按 `JsonProvider` 契约分组：

| 分组 | 方法 | 行为要点 |
| --- | --- | --- |
| 节点工厂 | `createJsonObject()` / `createJsonObject(Map)` / `createJsonArray()` / `createJsonArray(Collection)` / `createJsonNode(Object)` / `build()` / `buildArray()` | 返回 `ForyJsonObject` / `ForyJsonArray` / `ForyJsonNode` |
| 解析 | `parse(String)` / `parse(byte[])` | `"["` 开头按数组、否则按对象；入参 `null` 或解析异常返回空对象节点；`byte[]` 按 UTF-8 |
| 取容器 | `getJsonObject(String / byte[] / InputStream / InputStreamReader / InputStream+charset)`、`getJsonArray(String / byte[])`、`getJsonReference(String)` | 字符串版解析失败返回空容器；`getJsonObject(byte[])` 失败抛异常 |
| 对象映射 | `fromJson(String, Class<T>)`、`fromJson(byte[], Class<T>)`、`fromJson(InputStream / InputStreamReader / Reader, Class<T>)`、`fromJson(String)` → `Map`、`fromJson(String, Type)`、`fromJson(InputStream / Reader, Type)`、`fromJson(byte[], Charset)` → `JsonObject`、`fromJsonToList(String / InputStream, Class<T>)` | 失败统一抛 `RuntimeException("JSON 处理失败: ...")` |
| 生成 | `toJson(Object, String... ignores)`、`toJson(Object, Writer)`、`toJsonByte(Object)`、`toJSONBytes(Object)`、`toJSONString(Object)` | `toJson(null)` → `"null"`、`toJsonByte(null)` → 空数组；`ignores` 剔除顶层字段 |
| 其它 | `prettyFormat(Object)`、`toPrettyJson(Object)`、`isJson(Object)`、`toList(String)`、`validate(String)` | pretty 回退紧凑输出（fory-json 无内置缩进）；`isJson` 仅判断字符串是否 `{...}` / `[...]` 形态；`validate` 失败返回 `false` |

### 2.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 对象 ↔ 二进制 | `ForySerialization`（SPI 名 `fory` / `fury`） | 全局共享实例，线程安全 |
| 按类型取序列化器 | `new ForySerializer<>(User.class)` | 按类缓存 Fury，含实例池管理 |
| JSON → 对象 | `new ForyJsonProvider().fromJson(json, User.class)` | 失败抛 `RuntimeException` |
| 对象 → JSON | `toJson(obj)` / `toJsonByte(obj)` / `toJSONString(obj)` | 支持顶层字段剔除 |
| 解析 JSON 容器 | `getJsonObject(...)` / `getJsonArray(...)` / `parse(...)` | 字符串解析失败返回空容器 |
| 全局切换 JSON 实现 | `Json.setImplementation(new ForyJsonProvider())` | 见 `ForyJsonProvider` Javadoc |
| 门户注解兼容 | `@JsonName` / `@JsonIgnore` / `@JsonFormat` | 自动经 `JsonBeanMapper` 桥接（见 3.4） |
| 运维 | `ForySerializer.clearPool()` / `ForySerializer.getPoolSize()` | Fury 实例池管理 |

---

## 3. 快速开始

### 3.1 二进制序列化（SPI 按名获取）

```java
import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.spi.ServiceProvider;

// ServiceProvider 为 utils SPI 统一入口（见 common-starter 文档）
Serialization fory = ServiceProvider.of(Serialization.class).getExtension("fory");
// 别名等价：getExtension("fury")

byte[] data = fory.serialize(user);              // null → 空字节数组
User back = fory.deserialize(data, User.class);  // 空字节数组 → null
```

### 3.2 类型化序列化器

```java
import com.chua.common.support.serialize.Serializer;
import com.chua.fory.support.serialize.ForySerializer;

Serializer<User> serializer = new ForySerializer<>(User.class); // User 需实现 Serializable

byte[] bytes = serializer.serialize(user);
User back = serializer.deserialize(bytes);

ForySerializer.getPoolSize();   // 已缓存的 Fury 实例数
ForySerializer.clearPool();     // 释放全部实例
```

### 3.3 JSON

```java
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.fory.support.json.ForyJsonProvider;

ForyJsonProvider json = new ForyJsonProvider();

String text = json.toJson(user);                 // 对象 → JSON；null → "null"
User back = json.fromJson(text, User.class);     // JSON → 对象；失败抛 RuntimeException

JsonObject obj = json.getJsonObject("{\"a\":1}"); // 解析失败返回空对象
JsonArray arr = json.getJsonArray("[1,2,3]");     // 解析失败返回空数组

json.validate(text);            // 是否合法 JSON
json.isJson("{\"a\":1}");       // 字符串是否形如 {...} / [...]
json.toJson(user, "pwd");       // 剔除顶层字段 pwd 后输出
json.toList("[1,2,3]");         // JSON 数组 → List
```

### 3.4 门户注解桥接

fory-json 基于代码生成、只读取自身注解模型，无法原生识别 common-starter 的门户注解。目标类型的字段携带以下任一注解时，`ForyJsonProvider` 会先经 `com.chua.common.support.lang.json.annotation.JsonBeanMapper` 转为 `Map` 再交给 fory-json 编解码，保证门户注解在各 JSON 实现间行为一致（注解定义均位于 common-starter 的 `com.chua.common.support.lang.json.annotation` 包）：

| 注解 | 作用 |
| --- | --- |
| `JsonName` | 字段名映射 |
| `JsonIgnore` | 序列化忽略 |
| `JsonFormat` | 字段格式 |

```java
String out = new ForyJsonProvider().toJson(dto);              // 携带门户注解时自动桥接
Dto back = new ForyJsonProvider().fromJson(out, Dto.class);   // 反向同样桥接
```

### 3.5 全局切换 JSON 实现

```java
import com.chua.common.support.lang.json.Json;
import com.chua.fory.support.json.ForyJsonProvider;

Json.setImplementation(new ForyJsonProvider());   // 之后 Json 门面的节点类型随本实现切换
```

---

## 4. 配置项

**无运行时配置项。** 依据：模块全部 6 个源码类均未读取任何配置键（无 `System.getProperty`、无 Spring `@Value` / `@ConfigurationProperties`），`src/main/resources` 下也没有 properties / yml 配置文件；`pom.xml` 仅声明构建期属性 `project.build.sourceEncoding=UTF-8` 与 `maven.compiler.parameters=true`。

行为由代码内固定配置决定（不可通过外部配置修改）：

| 行为 | 固定取值 | 出处 |
| --- | --- | --- |
| 语言模式 | `Language.JAVA` | `ForySerializer` / `ForySerialization` |
| 引用跟踪 | 开启（`withRefTracking(true)`；`ForySerializer` 中常量 `REF_TRACKING = true`） | 支持循环引用 / 共享引用对象图 |
| 类注册 | `requireClassRegistration(false)` | 不强制注册类，动态序列化更灵活 |
| Guava 类型注册 | `registerGuavaTypes(false)`（仅 `ForySerialization`） | — |
| 类名守卫 | `setTypeChecker(... → DeserializationGuard.isAllowed(className))` | 反序列化黑名单拦截 |
| JSON 美化 | `prettyFormat` / `toPrettyJson` 回退紧凑输出 | 源码注释：fory-json 无内置缩进 |

---

## 5. SPI 扩展点

本模块只**实现** SPI，不定义新的扩展点。实现与注册名：

| 契约（定义于 common-starter） | 实现类 | SPI 名 |
| --- | --- | --- |
| `com.chua.common.support.serialize.Serializer` | `ForySerializer` | `fory`、`fury` |
| `com.chua.common.support.base.serialize.Serialization` | `ForySerialization` | `fory`、`fury` |
| `com.chua.common.support.lang.json.JsonProvider` | `ForyJsonProvider` | `fory` |

注册文件（`src/main/resources`）：

| 文件 | 内容 |
| --- | --- |
| `META-INF/extensions/com.chua.common.support.serialize.Serializer` | `fory=com.chua.fory.support.serialize.ForySerializer`、`fury=com.chua.fory.support.serialize.ForySerializer` |
| `META-INF/extensions/com.chua.common.support.base.serialize.Serialization` | `fory=com.chua.fory.support.serialize.ForySerialization`、`fury=com.chua.fory.support.serialize.ForySerialization` |
| `META-INF/extensions/com.chua.common.support.lang.json.JsonProvider` | `fory=com.chua.fory.support.json.ForyJsonProvider` |
| `META-INF/services/com.chua.common.support.base.serialize.Serialization` | `com.chua.fory.support.serialize.ForySerialization`（JDK ServiceLoader 兼容） |

索引生成方式：`ForyJsonProvider` 标注 `@AutoSpi("com.chua.common.support.lang.json.JsonProvider")`，由 `utils-support-ast-starter` 的 `AutoSpiAstProcessor` 在编译期生成 `META-INF/extensions` 索引；`ForySerializer` / `ForySerialization` 的同名索引为源码内维护的等价文件。

GraalVM native-image：`META-INF/native-image/com.chua/utils-support-fory-starter/` 内置 `reflect-config.json`（本模块类 + Fory 内部类 + JDK 常用类型）、`proxy-config.json`、`resource-config.json`。

同仓消费方（已验证）：common-starter 的 `RpcSerialization` 将 `fury` / `fory` 映射到 `ForySerialization`；`WalDispatcherProvider`、`ZmqDispatcherProvider`、`DispatcherConfig` 的序列化器名均支持 `fury` / `fory` / `jackson`。

---

## 6. 构建与依赖

```bash
mvn -o -q compile -DskipTests
```

依赖清单（版本由根 pom `dependencyManagement` 统一管理）：

| 依赖 | 版本 | 作用 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | 契约接口与工具 |
| `org.apache.fory:fory-core` | `1.7.8` | Fory 二进制序列化内核 |
| `org.apache.fory:fory-json` | `1.7.8` | `ForyJsonProvider` 的底层 JSON 引擎 |
| `com.chua:utils-support-ast-starter` | `${project.version}`，`provided` | 仅编译期：`@AutoSpi` 注解 + `AutoSpiAstProcessor` 生成 SPI 索引 |
| `org.projectlombok:lombok` | `${lombok.version}` | 注解处理器 |

> 编译插件中显式叠加了 lombok 与 `AutoSpiAstProcessor` 两个注解处理器：父级 `pluginManagement` 只声明 lombok，会禁用 classpath 处理器发现，因此必须在此显式声明才能启用 `@AutoSpi` 的编译期索引生成。

---

## 7. 边界：这些能力不在本模块

- **契约与门面**：`Serializer`、`Serialization`、`JsonProvider`、`Json`、`JsonObject` / `JsonArray` / `JsonNode`、`JsonBeanMapper`、门户注解 `JsonName` / `JsonIgnore` / `JsonFormat` 均定义在 `utils-support-common-starter`；本模块只是其 Fory 实现。
- **反序列化安全策略本体**：`DeserializationGuard` 及其策略位于 common-starter；本模块只通过 `setTypeChecker` 接入。
- **其它 JSON 实现**：jackson / gson / fastjson 等位于各自独立 starter，不在本模块。
- **其它二进制序列化实现**：protobuf、kryo 等位于各自模块，不在本模块。
- **pretty 输出**：fory-json 无内置缩进，`prettyFormat` / `toPrettyJson` 返回紧凑 JSON，不产生缩进格式。
- **线程安全边界**：本模块全部经 `ThreadSafeFory` 使用 Fory；`ForySerializer` 按类型缓存实例，类型无限增长时池会增长，需要时用 `clearPool()` 释放。
- **跨语言客户端**：跨语言互操作依赖 Fory 自身协议能力，本模块不提供其它语言的客户端代码。
