# utils-support-protobuf-starter

> utils 体系的 **Protobuf 序列化模块**：基于 protostuff 的注解驱动 Protobuf 编解码，无需 `.proto` 文件与 protoc 编译器，输出标准 protobuf 二进制格式。
>
> 版本：`4.0.0.51`｜JDK：`25`（`java.version=25`）｜包名：`com.chua.protobuf.support.serialize`

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
    <artifactId>utils-support-protobuf-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 基于 protostuff 的高性能 Protobuf 序列化模块，向 common-starter 的两个序列化 SPI（`Serializer`、`Serialization`）注册 `protobuf` 实现 | 不是 `.proto` 文件编译工具链，不含 protoc |
| 使用方式 | 注解驱动：消息类字段用 protostuff 的 `@Tag(N)` 标注序号，运行时由 `RuntimeSchema` 动态生成 Schema，零代码生成 | 不是 JSON / Kryo / Fory 等其它格式（同仓另有对应 starter） |
| 性能 | 每个实体类类型缓存一个 `Schema` 实例（`ConcurrentHashMap`），`LinkedBuffer` 通过 `ThreadLocal` 复用，线程安全 | — |
| 依赖 | 只依赖 `utils-support-common-starter`、`protostuff-core` / `protostuff-runtime` 1.8.0、编译期 `utils-support-ast-starter` | 不依赖 Spring、不含网络传输 / RPC、不含 jprotobuf（源码注释明确说明 protostuff 相比已停止维护的 jprotobuf 的优势） |

### 1.3 模块规模

- Java 源文件 **2** 个：`ProtobufSerializer`、`ProtobufSerialization`（均 `@since 4.0.0.42`）。
- SPI 扩展声明文件 **2** 个（`src/main/resources/META-INF/extensions/`）。
- 内置 GraalVM `native-image` 配置：`reflect-config.json` / `proxy-config.json` / `resource-config.json`。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 类型安全的序列化 / 反序列化 | `new ProtobufSerializer<>(User.class)` | 实现 `Serializer<T>`，泛型上界 `Serializable`；`serialize(T)` / `deserialize(byte[])` |
| SPI 按名获取通用序列化 | `ServiceProvider.of(Serialization.class).getExtension("protobuf")` | 实现 `Serialization`，`name()` 返回 `"protobuf"` |
| 直接使用通用实现 | `new ProtobufSerialization()` | `serialize(Object)` / `deserialize(byte[], Class<T>)`，方法声明 `throws Exception` |
| SPI 获取 `Serializer` | `ServiceProvider.of(Serializer.class).getExtension("protobuf")` | `META-INF/extensions` 已登记 `protobuf` 键 |
| 消息类字段标注 | protostuff 的 `@Tag(N)` 注解 | 字段序号，无需 `.proto` 文件 |
| 释放 Schema 缓存 | `ProtobufSerializer.clearPool()` / `ProtobufSerialization.clearPool()` | 适用于测试环境或需要释放资源的场景 |
| 查看缓存规模 | `ProtobufSerializer.getPoolSize()` | 返回当前缓存的 Schema 实例数量（仅 `ProtobufSerializer` 提供） |

### 2.2 行为细节（与源码实现一致）

| 行为 | `ProtobufSerializer<T>` | `ProtobufSerialization` |
| --- | --- | --- |
| `serialize(null)` | 返回空字节数组 `new byte[0]` | 返回空字节数组 `new byte[0]` |
| `deserialize(null / 空数组)` | 返回 `null` | 返回 `null` |
| 异常处理 | 捕获后包装为 `RuntimeException`，消息形如 `Protobuf serialize failed for <类名>` / `Protobuf deserialize failed for <类名>` | 不包装，直接 `throws Exception` 抛出 |
| Schema 缓存 | `ConcurrentHashMap<Class<?>, Schema<?>>`，`computeIfAbsent(clazz, RuntimeSchema::getSchema)`，线程安全可复用 | 同左 |
| 缓冲区 | `ThreadLocal<LinkedBuffer>`，`LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE)`，用后在 `finally` 中 `clear()` | 同左 |
| 编解码调用 | `ProtostuffIOUtil.toByteArray(object, schema, buffer)` / `schema.newMessage()` + `ProtostuffIOUtil.mergeFrom(bytes, result, schema)` | 同左 |

### 2.3 两个实现的区别（源码注释原文口径）

- `ProtobufSerializer` — 类型安全，需指定 `Class<T>`，实现 `Serializer<T>` 接口；
- `ProtobufSerialization` — 通用接口，实现 `Serialization` 接口，适合 SPI 发现。

---

## 3. 快速开始

### 3.1 定义消息类

字段用 protostuff 的 `@Tag(N)` 注解标注序号，并实现 `Serializable`（示例与源码类注释一致；`@Tag` 由 protostuff 依赖提供）：

```java
public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    @Tag(1)
    private String name;

    @Tag(2)
    private int age;

    // getter / setter 略
}
```

### 3.2 方式一：类型安全的 ProtobufSerializer

```java
import com.chua.protobuf.support.serialize.ProtobufSerializer;
import com.chua.common.support.serialize.Serializer;

Serializer<User> serializer = new ProtobufSerializer<>(User.class);
byte[] bytes = serializer.serialize(user);
User result = serializer.deserialize(bytes);
```

### 3.3 方式二：SPI 通用入口 ProtobufSerialization

```java
import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.spi.ServiceProvider;

Serialization serialization = ServiceProvider.of(Serialization.class).getExtension("protobuf");

byte[] data = serialization.serialize(user);
User back = serialization.deserialize(data, User.class);
```

### 3.4 GraalVM native-image

模块自带 `src/main/resources/META-INF/native-image/com.chua/utils-support-protobuf-starter/` 下的 `reflect-config.json` / `proxy-config.json` / `resource-config.json`，已登记本模块两个实现类、两个 SPI 接口以及 protostuff 的 `LinkedBuffer` / `ProtostuffIOUtil` / `Schema` / `RuntimeSchema`。

---

## 4. 配置项

**无。**

依据：本模块源码只有 2 个 Java 实现类（`ProtobufSerializer`、`ProtobufSerialization`）、2 个 `META-INF/extensions` SPI 索引文件和 3 个 `META-INF/native-image` 配置文件；`pom.xml` 中除编码与编译参数（`project.build.sourceEncoding=UTF-8`、`maven.compiler.parameters=true`）外没有任何配置键，模块内不存在 `@ConfigurationProperties`、配置文件或可调参数。

---

## 5. SPI 扩展点

本模块是 common-starter 序列化 SPI 的**实现提供方**，不是新的扩展点定义方。

| SPI 接口 | SPI key | 实现 | 注册方式 |
| --- | --- | --- | --- |
| `com.chua.common.support.serialize.Serializer` | `protobuf` | `com.chua.protobuf.support.serialize.ProtobufSerializer` | 类上 `@Spi("protobuf")` + `META-INF/extensions/com.chua.common.support.serialize.Serializer` 文件 |
| `com.chua.common.support.base.serialize.Serialization` | `protobuf` | `com.chua.protobuf.support.serialize.ProtobufSerialization` | 类上 `@Spi("protobuf")` + `@AutoSpi(value = "com.chua.common.support.base.serialize.Serialization")` 编译期生成 `META-INF/extensions/com.chua.common.support.base.serialize.Serialization` 索引 |

- SPI 索引文件内容均为一行：`protobuf=com.chua.protobuf.support.serialize.<实现类名>`。
- `@AutoSpi` 注解与 `AutoSpiAstProcessor` 来自 `utils-support-ast-starter`，在编译期自动生成 `META-INF/extensions` SPI 索引（该依赖为 `provided` 作用域，仅编译期使用）。
- 运行期获取：`ServiceProvider.of(接口.class).getExtension("protobuf")`。

---

## 6. 构建与依赖

### 6.1 依赖清单（`pom.xml`）

| 依赖 | 版本 | 作用域 | 说明 |
| --- | --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | compile | 提供 `Serializer` / `Serialization` SPI 接口与 `ServiceProvider` |
| `io.protostuff:protostuff-core` | 1.8.0 | compile | protostuff 核心 |
| `io.protostuff:protostuff-runtime` | 1.8.0 | compile | 运行时 `RuntimeSchema` 动态 Schema |
| `com.chua:utils-support-ast-starter` | `${project.version}` | provided | 仅编译期：`@AutoSpi` 注解 + `AutoSpiAstProcessor` 自动生成 SPI 索引 |
| `org.projectlombok:lombok` | `${lombok.version}`（根 pom 管理，1.18.48） | annotationProcessorPath | 注解处理器 |

### 6.2 编译参数

- 父模块：`com.chua:utils-support-extra-parent:4.0.0.51`（根 pom `utils-support-parent-starter:4.0.0.51`）。
- JDK：`java.version=25`；源码编码 `UTF-8`；`maven.compiler.parameters=true`。

### 6.3 构建命令

```bash
# 编译本模块（连同依赖模块一起反应堆构建）
mvn -o -q compile -DskipTests -pl utils-support-extra-parent/utils-support-protobuf-starter -am

# 安装到本地仓库
mvn -o -q install -DskipTests -pl utils-support-extra-parent/utils-support-protobuf-starter -am
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| 其它序列化格式（Fory、Gson、Fastjson、通用序列化门面） | 同仓 `utils-support-extra-parent` 下的 `utils-support-fory-starter`、`utils-support-gson-starter`、`utils-support-fastjson-starter`、`utils-support-serialize-starter` |
| `.proto` 文件编写、protoc 编译、gRPC 通道 | 不在本模块，模块内未提供 |
| 网络传输、RPC、服务端 / 客户端 | common-starter 的 `network` 体系，本模块只产出字节 |
| Spring Boot 自动装配、配置绑定 | 本模块无 Spring 依赖，无自动装配类 |
| jprotobuf | 源码注释明确以 protostuff 替代，模块内无 jprotobuf 依赖 |
| 序列化安全策略（反序列化白名单等） | common-starter 的 `serialize.DeserializationPolicy` / `DeserializationGuard`，本模块未涉及 |
