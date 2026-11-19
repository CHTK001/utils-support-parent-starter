# utils-support-milvus-starter

> utils 数据源家族中的 **Milvus 向量数据库接入模块**：把远端 Milvus（含 Zilliz Cloud）包装成统一的 `VectorStorage` 向量存储，并通过 `VectorStorageProvider` SPI 以名字 `milvus` 对外暴露。
>
> 版本：`4.0.0.51`（随父模块 `utils-support-datasource-parent`；本模块类自 `4.0.0.42` 起提供）｜JDK：`25`（父 POM `java.version=25`）｜包名：`com.chua.milvus.support`（子包 `storage` / `spi` / `configuration`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 行为细节](#4-行为细节)
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
    <artifactId>utils-support-milvus-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块 POM 描述：`Milvus向量数据库ORM`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 实现 | `AbstractVectorStorage`（即 `VectorStorage`）的 Milvus 远程实现，基于 `MilvusClientV2` SDK（v2 API） | 不是本地/内嵌向量库 |
| 接入 | 以 SPI 名 `milvus` 注册到 `VectorStorageProvider`：可链式构建，也可按键值动态创建 | 不提供 Spring 自动装配，没有 `application.yml` 配置前缀 |
| 范围 | 单个 collection 的增、删、改、Top-K 检索、统计、清空、刷盘 | 不做多集合管理、不做 Embedding、不管 Milvus 集群部署运维 |

**判断口径**：要把向量存到远端 Milvus / Zilliz Cloud 并做相似度检索时用本模块；纯内存验证用 common-starter 内置的 `memory` 实现，其它向量库见 `utils-support-datasource-parent` 下各 `*-starter`。

### 1.3 规模

- Java 源文件 **3** 个：`MilvusVectorStorage`、`MilvusVectorStorageProvider`、`MilvusStorageProperties`。
- SPI 声明文件 **1** 个（`META-INF/extensions/` 下）。
- GraalVM `native-image` 配置 **2** 个：`reflect-config.json`、`resource-config.json`。

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 链式创建 | `VectorStorageProvider.of("milvus").dimension(n).algorithm("COSINE").properties(props).build()` | 返回 `VectorStorage` |
| 按键值动态创建 | `VectorStorageProvider.createDynamic("milvus", dim, algo, configMap)` | `configMap` 的键见第 5 节 |
| 直接构造 | `new MilvusVectorStorage(dim, algorithm, host, port, collection, token)` | 构造时自动建集合、建索引并加载 |
| 写入向量 | `storage.add(id, float[])` | 维度必须与构造维度一致，否则抛 `IllegalArgumentException` |
| 检索 Top-K | `storage.search(query, topK)` | 返回 `List<Vector>`，按相似度排序 |
| 更新 / 删除 | `storage.update(id, vector)` / `storage.remove(id)` | id 不存在时返回 `false` |
| 刷盘 | `storage.release()` | flush 后新向量才可被搜索；`search` 前会自动补刷 |
| 统计 / 清空 / 关闭 | `storage.size()` / `storage.clear()` / `storage.close()` | `clear()` 会删除集合并重建 |

---

## 3. 快速开始

```java
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.milvus.support.configuration.MilvusStorageProperties;

import java.util.List;

public class MilvusDemo {
    public static void main(String[] args) {
        MilvusStorageProperties props = new MilvusStorageProperties()
                .setHost("127.0.0.1")      // 或完整 URI，如 https://xxx.zillizcloud.com
                .setPort(19530)            // 仅当 host 不含协议时生效
                .setCollection("vector_store");
                // 连 Zilliz Cloud 时再 .setToken("...")；自建实例留空

        try (VectorStorage storage = VectorStorageProvider.of("milvus")
                .dimension(4)
                .algorithm("COSINE")       // 支持 EUCLIDEAN / COSINE / DOT
                .properties(props)
                .build()) {

            storage.add("id-1", new float[]{0.1f, 0.2f, 0.3f, 0.4f});
            storage.add("id-2", new float[]{0.9f, 0.8f, 0.7f, 0.6f});
            storage.release();             // 刷盘，使新向量可被搜索

            List<Vector> top = storage.search(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, 5);
            top.forEach(v -> System.out.println(v.id()));

            System.out.println(storage.size());
        }                                  // close() 会先 flush 再关闭客户端
    }
}
```

也可以不用构建器，直接 `new MilvusVectorStorage(4, VectorCompareAlgorithm.cosine(), "127.0.0.1", 19530, "vector_store", null)`；若手上只有键值配置（如业务侧持久化的表单数据），用 `VectorStorageProvider.createDynamic("milvus", 4, VectorCompareAlgorithm.cosine(), configMap)`，全程无需按名称写分支。

---

## 4. 行为细节

### 4.1 集合模型

- 集合不存在时自动创建：主键字段 `id`（`VarChar`，`maxLength=64`，`autoID=false`），向量字段 `vector`，并开启动态字段（`enableDynamicField=true`）。
- 新建集合会为 `vector` 字段建 `AUTOINDEX` 索引（metricType 取自下表的映射结果）；随后总是执行 `loadCollection`。
- `clear()` 会 `dropCollection` 后重新走一遍「建集合 + 建索引 + 加载」。

### 4.2 距离算法映射

构造时传入的 `VectorCompareAlgorithm.name()` 按下表映射为 Milvus `IndexParam.MetricType`：

| 传入算法名 | Milvus MetricType |
| --- | --- |
| `EUCLIDEAN` / `L2` | `L2` |
| `DOT` / `DOT_PRODUCT` / `IP` | `IP` |
| 其它（含 `COSINE`）或 null | `COSINE` |

### 4.3 检索

- 每次搜索先向 Milvus 取 `topK * 5` 个候选，再用构造时传入的 `VectorCompareAlgorithm.compare()` 二次排序（rerank）后截断为 topK；`topK <= 0` 直接返回空列表。
- 结果 `Vector` 的 metadata 中带 `score`（Milvus 返回的相似度分）。

### 4.4 刷盘与关闭

- 写入、更新之后（以及删除命中后）内部标记「未刷盘」；`release()` 执行一次 `flush`；`search` 发现未刷盘会先自动 `release()`。
- `close()` 先尝试 flush（失败只记日志、不中断），再关闭 `MilvusClientV2`；关闭后再调用任何操作会抛 `IllegalStateException("向量存储已关闭")`。

### 4.5 线程安全

- `add` / `remove` / `update` / `search` / `clear` 均为 `synchronized` 方法。

---

## 5. 配置项

本模块**没有** Spring Boot `application.yml` 配置前缀：模块不含 Spring 依赖，也没有 `@ConfigurationProperties` 声明。`MilvusStorageProperties` 的 Javadoc 写明它「通过 Spring Boot 配置或 `VectorStorageProvider` 链式构建器传入」，但源码未提供具体前缀绑定。

实际生效的是 **SPI 键值配置**——由 `MilvusVectorStorageProvider.descriptor()` 声明、`toProperties(Map)` 消费，最终落到 `MilvusStorageProperties`（默认值如下）：

| 键 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `host` | 文本 | `127.0.0.1` | Milvus 服务地址；支持完整 URI（如 `https://xxx.zillizcloud.com`） |
| `port` | 数字 | `19530` | 仅当 `host` 不含协议时生效 |
| `collection` | 文本 | `vector_store` | 向量集合名 |
| `token` | 密码（secret） | 无（null） | Zilliz Cloud 必填；自建实例留空；前端不回显、后端不写日志 |

缺项回落默认值：`toProperties()` 只覆盖配置中出现的键，因此前端只提交改动过的字段也能正常工作。

---

## 6. SPI 扩展点

**本模块实现的 SPI**（接口定义在 `utils-support-common-starter`）：

- 接口：`com.chua.common.support.vector.VectorStorageProvider`
- SPI 名称：`milvus`（`@Spi(value = "milvus", order = 100)`）
- 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.vector.VectorStorageProvider`，内容一行：

  ```text
  milvus=com.chua.milvus.support.spi.MilvusVectorStorageProvider
  ```

- `descriptor()`：显示名 `Milvus 向量库`，声明上表 4 个配置字段；`requiresDataSource = false`（不需要宿主注入 `DataSource`）。
- `create(...)`：只接受 `MilvusStorageProperties` 类型的属性对象，否则抛 `IllegalArgumentException`。

**本模块不定义**新的 SPI 接口或扩展点（无）。

GraalVM native-image 支持：

- `reflect-config.json`：为 `MilvusVectorStorageProvider` 开启全部构造器/方法/字段反射。
- `resource-config.json`：包含 `META-INF/extensions/.*`（保证 SPI 注册文件进入镜像）。

---

## 7. 构建与依赖

- JDK：`25`（继承父 POM `java.version=25`）。
- 编译验证（仓库标准口径）：

  ```bash
  mvn -o -q compile -DskipTests
  ```

  要求 `EXIT=0`。

依赖清单（来自本模块 `pom.xml`）：

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父模块 | `VectorStorage` / `VectorStorageProvider` 等契约来源 |
| `com.chua:utils-support-datasource-starter` | 随父模块 | `provided` 作用域，由宿主提供 |
| `io.milvus:milvus-sdk-java` | `2.6.0` | 已排除 `io.grpc.*` 与 `io.perfmark.*` |
| `io.grpc:grpc-netty-shaded` / `grpc-protobuf` / `grpc-stub` / `grpc-api` / `grpc-core` / `grpc-context` / `grpc-util` | `1.84.0` | gRPC 运行时，单独显式引入 |
| `io.perfmark:perfmark-api` | `0.27.0` | gRPC 相关传递依赖补齐 |

---

## 8. 边界：这些能力不在本模块

| 能力 | 说明 |
| --- | --- |
| 内存向量库、向量数学、`VectorStorageBuilder` | 在 `utils-support-common-starter` 的 `com.chua.common.support.vector` 包 |
| 其它向量库实现 | JVector、ZVec、Elasticsearch 等在 `utils-support-datasource-parent` 下对应 `*-starter` |
| Embedding / 向量化 | 本模块不做；维度与向量数据均由调用方提供 |
| Spring 自动装配、管理端点、监控指标 | 无；本模块不含 Spring 依赖 |
| 多集合管理、分区 / 副本 / TTL 调优 | 源码未提供；一个 `MilvusVectorStorage` 实例只对应一个 collection，需要高级能力可自行使用 `MilvusClientV2` |
| Milvus 服务部署与运维 | 不在本模块范围 |
