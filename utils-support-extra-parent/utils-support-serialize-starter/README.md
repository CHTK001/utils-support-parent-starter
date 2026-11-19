# utils-support-serialize-starter

> 序列化支持模块，提供 Kryo、JSON、Java 等多种序列化方式及自动序列化能力（模块 `pom.xml` 描述原文）。
>
> 版本：`4.0.0.51`｜JDK：`25`（父 pom `java.version=25`）｜包名：`com.chua.serialize.support`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 包地图](#2-包地图)
- [3. 核心 API 与速查表](#3-核心-api-与速查表)
- [4. 快速开始](#4-快速开始)
- [5. 线上格式与安全](#5-线上格式与安全)
- [6. 配置项](#6-配置项)
- [7. SPI 扩展点](#7-spi-扩展点)
- [8. 构建与依赖](#8-构建与依赖)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 在 common 层 `Serializer` 契约之上，提供 Kryo 高性能实现与「Kryo → JSON → Java」自动降级实现 | 不重新定义序列化契约（契约在 `utils-support-common-starter`） |
| 依赖 | 只依赖 `utils-support-common-starter` 与 `com.esotericsoftware:kryo`（`5.7.0`） | 不依赖 Spring / Spring Boot，没有自动装配 |
| 安全 | Kryo 按类名还原前经 `DeserializationGuard` 守卫（黑名单策略） | 不提供加密、压缩能力 |
| 形态 | 纯 Java 工具库：静态门面 + 池化 + SPI 实现 | 不含 HTTP / RPC / 缓存 / 消息等集成能力 |

### 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 一行拿到「自动降级」序列化器 | `SerializeSupport.createAuto(User.class)` | Kryo → JSON → Java 三级降级 |
| 高性能 Kryo 直连 | `SerializeSupport.createKryo(User.class)` | 二进制序列化，内部按类型池化 Kryo 实例 |
| 高并发场景复用序列化器 | `SerializeSupport.createPool(User.class)` | `acquire()` / `release(serializer)` 借还模式 |
| 按类型缓存复用 | `SerializeSupport.getSerializer(User.class)` | 全局缓存，同类型只建一次 |
| 流式链式调用 | `SerializeSupport.createFlow(User.class)` | 返回 common 层 `SerializerFlow` |
| 按 SPI 名取实现 | `ServiceProvider.of(Serializer.class).getExtension("kryo")` | 注册名：`auto` / `kryo` |
| 追加自定义降级实现 | `autoSerializer.addSerializer(mySerializer)` | 返回分配的编解码编号 |
| 测试后释放资源 | `SerializeSupport.clearAll()` | 清缓存 + 清所有对象池 |

---

## 2. 包地图

共 5 个 Java 源文件，全部位于 `com.chua.serialize.support` 及其子包：

| 包 | 文件数 | 职责 | 代表类型 |
| --- | --- | --- | --- |
| `com.chua.serialize.support` | 1 | 静态门面，统一创建入口 | `SerializeSupport` |
| `com.chua.serialize.support.auto` | 2 | 自动降级序列化器与按类型缓存提供者 | `AutoSerializer`、`AutoSerializerProvider` |
| `com.chua.serialize.support.kryo` | 1 | Kryo 高性能序列化器（SPI `kryo`） | `KryoSerializer` |
| `com.chua.serialize.support.pool` | 1 | KryoSerializer 对象池管理器 | `KryoPoolManager` |

---

## 3. 核心 API 与速查表

### 3.1 SerializeSupport（静态门面）

私有构造、全静态方法；内部持有全局 `AutoSerializerProvider PROVIDER`。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `createFlow(Class<T>)` | `SerializerFlow` | 流式门面，默认装配 `AutoSerializer` |
| `createKryo(Class<T>)` | `KryoSerializer<T>` | 新建 Kryo 序列化器 |
| `createPool(Class<T>, int maxSize)` | `KryoPoolManager<T>` | 指定容量对象池（按类型单例） |
| `createPool(Class<T>)` | `KryoPoolManager<T>` | 默认容量 `16` |
| `createAuto(Class<T>)` | `AutoSerializer<T>` | 新建自动降级序列化器 |
| `getSerializer(Class<T>)` | `Serializer<T>` | 从 `PROVIDER` 缓存获取，无则新建 `AutoSerializer` |
| `getProvider()` | `AutoSerializerProvider` | 全局提供者实例 |
| `clearAll()` | `void` | `PROVIDER.clearCache()` + `KryoPoolManager.clearAll()` |

### 3.2 AutoSerializer（自动降级，SPI `auto`）

实现 `Serializer<T>`。降级链按固定优先级依次尝试，首选实现抛异常时降级到下一方案，全部失败抛 `RuntimeException`：

1. `KryoSerializer`（编号 `CODE_KRYO = 1`）
2. 构造时传入的自定义兜底序列化器（编号从 `CODE_USER_FIRST = 4` 起递增）
3. `JsonSerializer`（Jackson，编号 `CODE_JSON = 2`）
4. `JavaSerializer`（JDK 原生，编号 `CODE_JAVA = 3`）

| 方法 | 说明 |
| --- | --- |
| `serialize(T object)` | `null` 返回空数组；结果前拼 3 字节头 `'A' 'S' <编号>` |
| `deserialize(byte[] bytes)` | `null`/空数组返回 `null`；只按头部编号分派到对应实现，不做猜测式降级 |
| `addSerializer(Serializer<T>)` | 追加到链尾，返回分配的编解码编号；同类型已存在返回 `null` |
| `removeSerializer(Serializer<T>)` | 按引用移除，返回是否成功 |
| `getSerializerCount()` / `getSerializerNames()` | 链长度 / 实现类简单名列表 |

> 反序列化时若头部缺失、被篡改或编号未登记，抛 `IllegalArgumentException`。**跨进程两端必须以同样顺序构造降级链**，否则编号对不上会直接抛异常。

### 3.3 KryoSerializer（高性能，SPI `kryo`）

实现 `Serializer<T>`。Kryo 实例非线程安全，因此内部按实体类型缓存 `Pool<Kryo>`（每类型上限 `MAX_POOL_CAPACITY = 32`），每次编解码借出、用完归还。

| 方法 | 说明 |
| --- | --- |
| `serialize(T object)` | `null` 返回空数组；`writeClassAndObject` 写出 |
| `deserialize(byte[] bytes)` | `null`/空数组返回 `null`；`readClassAndObject` 还原 |
| `static clearPool()` | 清空所有类型的 Kryo 实例池 |
| `static getPoolSize()` | 已缓存对象池的类型数量 |

Kryo 实例配置：`setReferences(true)`（允许循环引用）、`setRegistrationRequired(false)`（不要求注册类）、`DefaultInstantiatorStrategy`；类解析器为内部 `GuardedClassResolver`，在读到报文类名后先执行 `DeserializationGuard.requireAllowed(className)` 再加载。

### 3.4 KryoPoolManager（序列化器对象池）

池化 `KryoSerializer` 包装实例，`acquire()` / `release()` 借还模式。池容器为非线程安全双端队列，所有触碰容器的方法（`acquire` / `release` / `clear` / `getPoolSize` / `stats`）都在实例监视器上同步。

| 方法 | 说明 |
| --- | --- |
| `static getInstance(Class<S>, int maxSize)` | 按类型单例（缓存于 `MANAGERS`；`maxSize` 仅在该类型首次创建时生效） |
| `static getInstance(Class<S>)` | 同上，默认容量 `16` |
| `acquire()` | 池中有空闲则复用，否则新建 |
| `release(KryoSerializer<T>)` | `null` 直接忽略；池未满回收，已满丢弃 |
| `getPoolSize()` / `getActiveCount()` / `getTotalCreated()` | 空闲数 / 活跃数 / 累计创建数 |
| `getMaxSize()` / `getClazz()` | 容量上限 / 管理的实体类型 |
| `clear()` | 清空本池并重置计数器 |
| `static clearAll()` | 清所有管理器 + `KryoSerializer.clearPool()` |
| `stats()` | 形如 `KryoPoolManager{clazz=User, pool=0, active=1, totalCreated=1, max=16}` |

### 3.5 AutoSerializerProvider（按类型缓存）

实现 common 层 `SerializerProvider`，用 `ConcurrentHashMap` 按实体类型缓存 `AutoSerializer`。

| 方法 | 说明 |
| --- | --- |
| `getSerializer(Class<T>)` | 返回 `Serializer<T>`，无则新建并缓存 |
| `getAutoSerializer(Class<T>)` | 同上，返回具体类型 `AutoSerializer<T>` |
| `clearCache()` / `getCacheSize()` | 清空缓存 / 当前缓存数量 |

---

## 4. 快速开始

### 4.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-serialize-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 4.2 最短可运行示例

```java
import com.chua.serialize.support.SerializeSupport;
import com.chua.serialize.support.auto.AutoSerializer;
import com.chua.serialize.support.kryo.KryoSerializer;
import com.chua.serialize.support.pool.KryoPoolManager;

import java.io.Serializable;

public class SerializeDemo {

    public static class User implements Serializable {
        private String name;
        private int age;

        public User() { }
        public User(String name, int age) { this.name = name; this.age = age; }
        // getter / setter 省略
    }

    public static void main(String[] args) {
        User user = new User("张三", 25);

        // 1) 自动降级：Kryo 失败 → JSON → Java 兜底
        AutoSerializer<User> auto = SerializeSupport.createAuto(User.class);
        byte[] bytes = auto.serialize(user);
        User back = auto.deserialize(bytes);

        // 2) Kryo 直连
        KryoSerializer<User> kryo = SerializeSupport.createKryo(User.class);
        byte[] kryoBytes = kryo.serialize(user);

        // 3) 对象池：借出 → 使用 → 归还
        KryoPoolManager<User> pool = SerializeSupport.createPool(User.class);
        KryoSerializer<User> serializer = pool.acquire();
        try {
            byte[] pooled = serializer.serialize(user);
        } finally {
            pool.release(serializer);
        }

        // 4) 按类型缓存
        com.chua.common.support.serialize.Serializer<User> cached =
                SerializeSupport.getSerializer(User.class);

        // 5) 测试或停机前统一释放
        SerializeSupport.clearAll();
    }
}
```

> 注意：`SerializerFlow#deserialize(byte[], Class)` 的实现固定使用 `JsonSerializer`（common 层源码行为）。若字节来自 `AutoSerializer`（带 `'A' 'S'` 头），请用 `AutoSerializer#deserialize(byte[])` 还原，或用不带类型参数的 `deserialize(byte[])`。

---

## 5. 线上格式与安全

### 5.1 AutoSerializer 字节头

`serialize` 产出为 `3 字节头 + 正文`：

```
+-----+-----+--------+---------------------+
| 'A' | 'S' | 编号    | 实际序列化正文        |
+-----+-----+--------+---------------------+
  0x41  0x53  1 字节   Kryo / JSON / Java / 自定义
```

- 已登记编号：Kryo = `1`、JSON = `2`、Java = `3`，自定义实现从 `4` 起按加入顺序递增；
- 反序列化**只按编号分派**，不逐个尝试——Kryo / JSON / JDK 字节流之间没有互斥保证，猜错可能静默还原出错误对象。

### 5.2 反序列化安全

- `KryoSerializer` 关闭类注册（`registrationRequired=false`）意味着报文中的任意类名都可被还原，与 JDK 原生序列化同属反序列化攻击面；
- 因此 `GuardedClassResolver` 在 `getTypeByName` 处接入 common 层 `DeserializationGuard.requireAllowed(className)`，命中黑名单即抛 `SecurityException`；
- 策略通过 `DeserializationPolicy` SPI 加载，默认内置 `DenyListDeserializationPolicy`，第三方可零侵入收紧（只能收紧不能放宽）。

---

## 6. 配置项

**无。** 依据：本模块源码中没有配置类、`@ConfigurationProperties`、`properties` / `yml` 配置资源；引入依赖即可直接使用，所有行为由源码常量决定：

| 常量 | 值 | 位置 | 含义 |
| --- | --- | --- | --- |
| `MAX_POOL_CAPACITY` | `32` | `KryoSerializer` | 每个实体类型的 Kryo 实例池上限 |
| 默认池容量 | `16` | `KryoPoolManager.getInstance(Class)` | `createPool(Class)` 的默认 `maxSize` |
| `HEADER_LENGTH` | `3` | `AutoSerializer` | 字节头长度（`'A' 'S' 编号`） |
| `CODE_KRYO` / `CODE_JSON` / `CODE_JAVA` | `1` / `2` / `3` | `AutoSerializer` | 内置实现的编解码编号 |
| `CODE_USER_FIRST` | `4` | `AutoSerializer` | 自定义序列化器起始编号 |

---

## 7. SPI 扩展点

### 7.1 本模块已注册的 SPI 实现

注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.serialize.Serializer`，内容为：

```properties
auto=com.chua.serialize.support.auto.AutoSerializer
kryo=com.chua.serialize.support.kryo.KryoSerializer
```

即可通过 common 层 SPI 按名获取：

```java
Serializer<User> auto = ServiceProvider.of(Serializer.class).getExtension("auto");
Serializer<User> kryo = ServiceProvider.of(Serializer.class).getExtension("kryo");
```

### 7.2 自定义序列化器（降级链扩展）

实现 `Serializer<T>` 后，两种接入方式：

```java
// 方式一：构造时插入降级链（编号从 4 起递增，位于 Kryo 之后、JSON 之前）
AutoSerializer<User> auto = new AutoSerializer<>(User.class, new MySerializer<>());

// 方式二：追加到链尾
Byte code = auto.addSerializer(new MySerializer<>());   // 返回分配的编号；同类型重复返回 null
auto.removeSerializer(serializer);                      // 按引用移除
```

### 7.3 反序列化策略扩展

common 层 `DeserializationPolicy` 为 SPI 扩展点：注册后 `DeserializationGuard` 会收集全部策略做 AND 判定，任一策略拒绝即拒绝。本模块的 `KryoSerializer` 与 common 的 `JavaSerializer` 均经该守卫。

---

## 8. 构建与依赖

### 8.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-serialize-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 8.2 依赖清单（来自本模块 `pom.xml`）

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `4.0.0.51` | 提供 `Serializer` / `SerializerProvider` / `SerializerFlow` / `JsonSerializer` / `JavaSerializer` / `DeserializationGuard` 等契约与默认实现 |
| `com.esotericsoftware:kryo` | `5.7.0`（由根 pom `dependencyManagement` 统一管理） | 高性能二进制序列化框架 |

父模块：`com.chua:utils-support-extra-parent:4.0.0.51`；JDK `25`。

### 8.3 构建

```bash
mvn -o -q compile -DskipTests
```

另含 GraalVM native-image 配置：`META-INF/native-image/com.chua/utils-support-serialize-starter/` 下的 `reflect-config.json` 与 `resource-config.json`。

---

## 9. 边界：这些能力不在本模块

| 能力 | 位置 |
| --- | --- |
| `Serializer` / `SerializerProvider` / `SerializerFlow` 契约与 `JsonSerializer`、`JavaSerializer` 默认实现 | `utils-support-common-starter`（`com.chua.common.support.serialize`） |
| 反序列化黑名单策略 `DeserializationPolicy` / `DenyListDeserializationPolicy` 与 `DeserializationGuard` | `utils-support-common-starter` |
| Spring Boot 自动装配（auto-configuration、`spring.factories` / `AutoConfiguration.imports`） | 本模块没有，纯 Java 工具库 |
| 其它序列化格式（Protobuf、Fury、Avro 等） | 本模块没有 |
| 压缩、加密、签名 | 本模块没有 |
| RPC / 缓存 / 消息队列等集成场景 | 本模块没有，只负责对象 ↔ 字节数组 |
| 跨语言（非 JVM）反序列化 | Kryo 二进制格式面向 Java 对象图；跨语言场景请用 JSON（`JsonSerializer` 或 `AutoSerializer` 的 JSON 级） |
