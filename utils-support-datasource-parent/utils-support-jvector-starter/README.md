# utils-support-jvector-starter

> utils 体系的 **JVector 本地向量库 starter**：把 [JVector](https://github.com/jbellis/jvector)（`io.github.jbellis:jvector`）封装成 `VectorStorageProvider` SPI 实现，提供进程内 CPU 向量索引（HNSW 图 + PQ 压缩），无需任何外部服务；`ON_DISK` 模式下索引与向量落盘，重启不丢数据。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.jvector.support.*`｜SPI 名称：`jvector`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 快速开始](#3-快速开始)
- [4. 三种存储模式](#4-三种存储模式)
- [5. 核心 API](#5-核心-api)
- [6. 配置项](#6-配置项)
- [7. SPI 扩展点与注册](#7-spi-扩展点与注册)
- [8. 检索行为与容错](#8-检索行为与容错)
- [9. 构建与依赖](#9-构建与依赖)
- [10. 边界：这些能力不在本模块](#10-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-jvector-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `VectorStorageProvider` 的 **`jvector` SPI 实现**：进程内向量索引门面 `JVectorVectorStorage` | 不是独立的向量数据库服务，也没有网络端口 |
| 能力 | 向量 add / search / update / remove / removeByIdPrefix / size / clear / rebuild / close，三种存储模式（纯内存、磁盘持久化、超内存 PQ 压缩） | 不做 Embedding 向量化，不提供 REST 管理接口 |
| 依赖 | 只依赖 `utils-support-common-starter`（契约）、`utils-support-datasource-starter`（provided）、`io.github.jbellis:jvector:4.0.1` | 不依赖 Spring，不依赖任何向量数据库服务端 |
| 配置 | `JVectorStorageProperties`（POJO）+ SPI 键值配置（`mode` / `indexPath` / `graphM` 等） | 无 `application.yml` 属性绑定，无 Spring Boot starter 自动装配 |

**判断口径**：本模块是「数据源/存储」家族（`utils-support-datasource-parent`）中的一员，只负责把 JVector 适配成 utils 的 `VectorStorage` 契约；契约接口本身（`VectorStorage`、`VectorStorageProvider`、`Vector`、`VectorCompareAlgorithm`）都在 `utils-support-common-starter`。

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 按链式风格创建 | `VectorStorageProvider.of("jvector").dimension(128).algorithm("COSINE").properties(props).build()` | 返回 `VectorStorage` |
| 按键值配置动态创建 | `VectorStorageProvider.createDynamic("jvector", dimension, algorithm, configMap)` | `configMap` 的键见[配置项](#6-配置项)，内部走 `toProperties(Map)` |
| 直接构造实现 | `new JVectorVectorStorage(dimension, algorithm)` 或 `new JVectorVectorStorage(dimension, algorithm, properties)` | 不经 SPI，适合测试 |
| 写入 / 检索 | `storage.add(id, float[])`、`storage.search(query, topK)` | 结果 `Vector.metadata().get("score")` 为相似度分数 |
| 更新 / 删除 | `storage.update(id, float[])`、`storage.remove(id)`、`storage.removeByIdPrefix(idPrefix)` | 按前缀批量删除常用于按知识库 ID 清理向量 |
| 持久化与重建 | `storage.rebuild()`、`storage.close()` | `ON_DISK` 模式 `close()` 时把脏向量与图落盘 |
| 前端动态表单 | `new JVectorVectorStorageProvider().descriptor()` | 返回 `VectorStorageDescriptor`，声明全部配置项 |
| 键值 → 属性对象 | `new JVectorVectorStorageProvider().toProperties(Map<String,Object>)` | 缺项回落 `JVectorStorageProperties` 默认值 |
| 列出全部向量库 SPI | `VectorStorageProvider.providers()` | 含 `"jvector"`、`"memory"` 等 |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-jvector-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例（SPI 链式风格）

```java
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.jvector.support.configuration.JVectorStorageProperties;

import java.util.List;

public class JVectorDemo {
    public static void main(String[] args) {
        JVectorStorageProperties props = new JVectorStorageProperties();
        props.setMode(JVectorStorageProperties.Mode.ON_DISK);   // 磁盘持久化，重启不丢
        props.setIndexPath("./jvector-index");                  // 索引与 .vectors 侧录文件路径

        try (VectorStorage storage = VectorStorageProvider.of("jvector")
                .dimension(4)
                .algorithm("COSINE")
                .properties(props)
                .build()) {

            storage.add("doc-1", new float[]{0.1f, 0.2f, 0.3f, 0.4f});
            storage.add("doc-2", new float[]{0.9f, 0.8f, 0.7f, 0.6f});

            List<Vector> hits = storage.search(new float[]{0.1f, 0.2f, 0.3f, 0.35f}, 5);
            for (Vector hit : hits) {
                System.out.println(hit.id() + " score=" + hit.metadata().get("score"));
            }
        }   // close() 时 ON_DISK 模式自动把向量与图落盘
    }
}
```

### 3.3 键值配置动态创建（管理后台场景）

```java
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;

import java.util.Map;

VectorStorage storage = VectorStorageProvider.createDynamic("jvector", 4,
        VectorCompareAlgorithm.cosine(),
        Map.of(
                "mode", "ON_DISK",
                "indexPath", "./jvector-index",
                "graphM", "32",
                "graphEfConstruction", "100",
                "searchOverquery", "2",
                "prepareOnStartup", "true"));
```

`createDynamic` 内部依次调用 `find("jvector")` → `descriptor()` 声明的键 → `toProperties(Map)` → `create(dimension, algorithm, properties)`，全程无按名称硬编码分支。

### 3.4 直接构造（跳过 SPI）

```java
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.jvector.support.configuration.JVectorStorageProperties;
import com.chua.jvector.support.storage.JVectorVectorStorage;

JVectorVectorStorage storage = new JVectorVectorStorage(
        4, VectorCompareAlgorithm.cosine(), new JVectorStorageProperties());
```

---

## 4. 三种存储模式

`JVectorStorageProperties.Mode`（枚举值与 `JVectorVectorStorage.createStrategy()` 中的分发一一对应）：

| 模式 | 策略类（内部） | 行为 | 适用 |
| --- | --- | --- | --- |
| `MEMORY` | `EagerMemoryStrategy` | 纯内存 HNSW 图，`add`/`remove`/`update` 后图置空、检索时重建；图搜索失败自动降级暴力扫描 | 小数据集、测试 |
| `ON_DISK` | `DiskStrategy` | 内存建图后 `OnDiskGraphIndex.write(...)` 落盘，加载用 `OnDiskGraphIndex.load(...)`；原始向量写入侧录文件 `<indexPath>.vectors`；`close()` 时把脏数据落盘 | 默认推荐，重启不丢 |
| `LARGER_THAN_MEMORY` | `LargerThanMemoryStrategy` | 先用 `ProductQuantization` 训练 PQ 码本并编码，再用 `BuildScoreProvider.pqBuildScoreProvider` 建图；检索两阶段：PQ 粗排 + 精确分数精排；全精度向量落 `<indexPath>.vectors` | 超内存大数据集 |

> 注意：`JVectorStorageProperties` 的 `mode` 字段默认是 `LARGER_THAN_MEMORY`；而 SPI 表单（`descriptor()`）里的默认值是 `ON_DISK`。走 SPI 键值配置且不填 `mode` 时，实际生效的是 `ON_DISK`。

---

## 5. 核心 API

### 5.1 JVectorVectorStorage（实现 `VectorStorage`，继承 `AbstractVectorStorage`）

| 方法 | 说明 |
| --- | --- |
| `JVectorVectorStorage(int dimension, VectorCompareAlgorithm algorithm)` | 两参构造，配置缺省为 `new JVectorStorageProperties()` |
| `JVectorVectorStorage(int dimension, VectorCompareAlgorithm algorithm, JVectorStorageProperties properties)` | 全参构造；`properties` 传 `null` 等价于默认配置 |
| `boolean add(String id, float[] vector)` | 添加向量；维度不匹配抛 `IllegalArgumentException`；重复 `id` 返回 `false` |
| `boolean add(Vector vector)` | 接口 default 方法，委托 `add(vector.id(), vector.data())` |
| `List<Vector> search(float[] query, int topK)` | Top-K 相似检索，结果按相似度降序 |
| `List<Vector> search(Vector query, int topK)` | 接口 default 方法，委托 `search(query.data(), topK)` |
| `boolean update(String id, float[] vector)` | 更新向量；`id` 不存在返回 `false`；维度不匹配抛 `IllegalArgumentException` |
| `boolean remove(String id)` | 删除向量（swap-remove 保持序数紧凑），并使图索引失效 |
| `int removeByIdPrefix(String idPrefix)` | 按标识前缀批量删除（如按知识库 ID 前缀清理），返回实际删除条数；前缀为空返回 0 |
| `int size()` | 当前向量条数 |
| `void clear()` | 清空内存状态并删除磁盘文件 |
| `void rebuild()` | 关闭当前图并重建（`ON_DISK`/`LARGER_THAN_MEMORY` 模式会先落盘再建图） |
| `void close()` | 释放图资源；`ON_DISK`/`LARGER_THAN_MEMORY` 模式把未落盘向量写入侧录文件 |
| `int dimension()` | 返回构造时声明的维度，已关闭时抛 `IllegalStateException` |

### 5.2 Vector 与分数

检索结果为 `com.chua.common.support.vector.Vector`（record：`id` / `data` / `metadata` / `content`）。本模块写入的 `metadata` 固定含一个键：

- `"score"`：`Double` 类型，来自 JVector 的 `SearchScoreProvider` 精确分数（`ON_DISK`/`LARGER_THAN_MEMORY` 精排）或降级扫描时的算法相似度。

分数含义取决于所选 `VectorCompareAlgorithm`：`COSINE` → 余弦相似度、`DOT` → 点积、`EUCLIDEAN`（默认）→ 欧氏距离。不同算法/不同向量库之间的分数不可直接比较。

### 5.3 相似度算法映射

`JVectorVectorStorage` 内部把 `VectorCompareAlgorithm.name()` 大写后映射为 JVector 的 `VectorSimilarityFunction`：

| 传入算法名 | JVector 度量 |
| --- | --- |
| `COSINE` | `VectorSimilarityFunction.COSINE` |
| `DOT` / `DOT_PRODUCT` | `VectorSimilarityFunction.DOT_PRODUCT` |
| 其它 / `null` | `VectorSimilarityFunction.EUCLIDEAN` |

内置工厂：`VectorCompareAlgorithm.euclidean()`、`VectorCompareAlgorithm.cosine()`、`VectorCompareAlgorithm.dotProduct()`；`VectorStorageBuilder.algorithm(String)` 支持 `EUCLIDEAN` / `COSINE` / `DOT`。

---

## 6. 配置项

### 6.1 JVectorStorageProperties 字段

| 字段 | 类型 | 默认值 | 生效模式 | 说明 |
| --- | --- | --- | --- | --- |
| `mode` | `Mode` 枚举 | `LARGER_THAN_MEMORY` | 全部 | `MEMORY` / `ON_DISK` / `LARGER_THAN_MEMORY` |
| `indexPath` | `String` | `./jvector-index` | `ON_DISK`、`LARGER_THAN_MEMORY` | 磁盘索引文件路径；向量侧录文件为 `<indexPath>.vectors` |
| `graphM` | `int` | `32` | 全部 | 图最大度数，越大召回越高、内存开销越大 |
| `graphEfConstruction` | `int` | `100` | 全部 | 建图阶段候选集深度，越大建图越慢、索引越准 |
| `searchOverquery` | `float` | `2.0f` | `ON_DISK`、`LARGER_THAN_MEMORY` | 检索候选集相对 `topK` 的倍数（`ef = max(topK, topK * searchOverquery)`）；`MEMORY` 模式固定取 `topK * 5` |
| `pqSubspaces` | `int` | `64` | `LARGER_THAN_MEMORY` | PQ 子空间数量；实际生效值被钳制到 `≤ 维度` |
| `pqCentroidsPerSubspace` | `int` | `256` | `LARGER_THAN_MEMORY` | 每个子空间码本数量；实际生效值被钳制到 `≤ 当前向量条数` |
| `pqParallelism` | `int` | `0` | `LARGER_THAN_MEMORY` | PQ 编码并行度；`0` 表示使用 `ForkJoinPool.commonPool()` |
| `prepareOnStartup` | `boolean` | `true` | — | SPI 表单声明的「启动即预构建」开关；`toProperties` 会写入该字段，但当前 `JVectorVectorStorage` 未读取它，不影响运行行为 |

### 6.2 SPI 键值配置（`JVectorVectorStorageProvider` 常量）

| 常量 | 键名 | 对应 setter |
| --- | --- | --- |
| `KEY_MODE` | `mode` | `setMode(Mode.valueOf(mode.toUpperCase()))`，非法值抛 `IllegalArgumentException` 并列出可选项 |
| `KEY_INDEX_PATH` | `indexPath` | `setIndexPath(String)` |
| `KEY_GRAPH_M` | `graphM` | `setGraphM(Integer)` |
| `KEY_GRAPH_EF_CONSTRUCTION` | `graphEfConstruction` | `setGraphEfConstruction(Integer)` |
| `KEY_SEARCH_OVERQUERY` | `searchOverquery` | `setSearchOverquery(Float)` |
| `KEY_PREPARE_ON_STARTUP` | `prepareOnStartup` | `setPrepareOnStartup(Boolean)`，接受 `true/false/1/0/on/off/yes/no` |

键值缺省时一律回落 `JVectorStorageProperties` 自带默认值；数字无法解析时忽略该项（保持默认）。

---

## 7. SPI 扩展点与注册

### 7.1 注册声明

本模块通过 utils 的 SPI 机制向 `com.chua.common.support.vector.VectorStorageProvider` 注册实现，声明文件：

```text
src/main/resources/META-INF/extensions/com.chua.common.support.vector.VectorStorageProvider
```

内容：

```text
jvector=com.chua.jvector.support.spi.JVectorVectorStorageProvider
```

### 7.2 实现类

`JVectorVectorStorageProvider` 标注 `@Spi(value = "jvector", order = 100)`，实现三个核心方法：

| 方法 | 说明 |
| --- | --- |
| `String name()` | 返回 `"jvector"` |
| `VectorStorageDescriptor descriptor()` | 返回配置描述：标题「JVector 本地向量库」，描述「进程内 CPU 向量索引，无需外部服务；ON_DISK 模式索引落盘，重启不丢数据」；6 个字段见[6.2](#62-spi-键值配置jvectorvectorstorageprovider-常量)，其中 `indexPath` 为 `path` 类型（由后端文件系统接口选取，不允许前端自由拼路径），`mode` 为必填下拉（默认 `ON_DISK`） |
| `Object toProperties(Map<String, Object> config)` | 把键值配置转换为 `JVectorStorageProperties` |
| `VectorStorage create(int dimension, VectorCompareAlgorithm algorithm, Object properties)` | `properties instanceof JVectorStorageProperties` 时直接使用，否则用默认配置，返回 `new JVectorVectorStorage(...)` |

### 7.3 扩展方式

本模块是 **SPI 实现方**，不是扩展点：新增向量库应另建 starter，实现 `VectorStorageProvider` 并在自家 `META-INF/extensions/` 登记，`VectorStorageBuilder` / `VectorStorageProvider.of(...)` 无需改动即可发现。GraalVM `native-image` 配置已随模块提供：

- `META-INF/native-image/com.chua/utils-support-jvector-starter/reflect-config.json`：放开 `JVectorVectorStorageProvider` 的全部构造器/方法/字段反射；
- `META-INF/native-image/com.chua/utils-support-jvector-starter/resource-config.json`：打包 `META-INF/extensions/.*`。

---

## 8. 检索行为与容错

以下行为均可在 `JVectorVectorStorage` 源码中验证：

- **降级扫描**：图搜索抛异常时（图索引因维度/度量变更失效等），三种模式都会销毁图并降级为线性暴力扫描（`linearScan` / `bruteForceSearch`），保证检索不因索引损坏而整体失败；`MEMORY` 模式在无自定义算法时走 `bruteForceCosine`。
- **维度变更自愈**：`ON_DISK`/`LARGER_THAN_MEMORY` 加载持久化向量前先做 `dimensionsMatch` 校验；与当前 embedding 维度不一致时丢弃旧索引与侧录文件，等待重建，避免检索期抛底层维度异常。
- **幂等写**：`add` 重复 `id` 返回 `false` 不覆盖；更新请用 `update`。
- **线程模型**：所有公开方法均为 `synchronized`，进程内并发安全；未提供跨进程文件锁，多个进程不要共享同一个 `indexPath` 同时写。
- **失败可见**：`close()`/`clear()` 落盘失败会记录 error 日志（`log.error`），不静默吞掉；`saveVectors` 抛 `UncheckedIOException`。

---

## 9. 构建与依赖

### 9.1 依赖关系

```text
utils-support-jvector-starter
├── utils-support-common-starter          （VectorStorage / SPI / 工具契约）
├── utils-support-datasource-starter      （provided，仅编译期）
└── io.github.jbellis:jvector:4.0.1
```

- 父模块：`utils-support-datasource-parent`（聚合在 `utils-support-parent-starter:4.0.0.51` 下）。
- `utils-support-datasource-starter` 为 `provided` 作用域：本模块源码未 import 它的类，仅保持数据源家族的编译期关联，运行期不需要。

### 9.2 构建

```bash
cd utils-support-parent-starter
mvn -o -q compile -DskipTests       # 编译本仓库（含本模块）
mvn -pl utils-support-datasource-parent/utils-support-jvector-starter -am install -DskipTests
```

仓库编码要求：源码 UTF-8 无 BOM（`project.build.sourceEncoding=UTF-8`）。

---

## 10. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| `VectorStorage` / `VectorStorageProvider` / `Vector` / `VectorCompareAlgorithm` 等契约与 `MemoryProvider` | `utils-support-common-starter` |
| 远程向量库（Milvus 等） | 各自的 `*-starter`（如 `utils-support-milvus-starter`） |
| Embedding 向量化、RAG 编排 | AI 相关模块，本模块只存取已生成的 `float[]` |
| Spring Boot 自动装配、`application.yml` 属性绑定、HTTP 管理端点 | 无（本模块是纯工具库，实例由 `VectorStorageBuilder` / `VectorStorageProvider` SPI 装配） |
| 跨进程共享索引、分布式检索 | 无（进程内 `synchronized`，未提供文件锁与网络协议） |
| 向量 metric 监控、路由选择 | 上层业务模块 |
