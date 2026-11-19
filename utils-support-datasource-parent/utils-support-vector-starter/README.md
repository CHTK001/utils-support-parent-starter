# utils-support-vector-starter

> GPU 加速向量搜索 Starter：在 `VectorStorageProvider` SPI 名 `vector` 下提供「自动选后端」工厂 —— 有 NVIDIA GPU 且 cuVS 可用时走 GPU（CAGRA / HNSW / BruteForce），否则自动降级到 jvector CPU 实现；对上层只暴露统一的 `VectorStorage` 增删改查契约。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`；基准脚本额外启用 `jdk.incubator.vector`）｜包名：`com.chua.vector.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 后端选择与降级规则](#4-后端选择与降级规则)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 性能基准与脚本](#8-性能基准与脚本)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-vector-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | SPI 名 `vector` 的**自动选后端工厂**（`VectorStorageProviderFactory`）：按硬件条件在 cuVS GPU 与 jvector CPU 之间选择 | 不是新的向量索引算法实现，也不绑定某个具体向量库服务 |
| GPU 侧 | `CuvsVectorStorage`：反射调用 `com.nvidia.cuvs.*`（CAGRA / HNSW / BruteForce 三种索引策略），无编译期 cuVS 依赖 | 不负责安装 NVIDIA 驱动、CUDA、cuVS 原生库（运行时外部环境） |
| CPU 侧 | `JvectorVectorStorageDelegate`：把配置映射后复用 `utils-support-jvector-starter` 的 `JVectorVectorStorage` | 不在本模块重复实现 jvector 索引 |
| 契约 | 实现 common-starter 的 `VectorStorageProvider` SPI，产出统一的 `VectorStorage` | 不含 Spring 自动装配、不含 HTTP 端点、不含数据库表与 Mapper |
| 可靠性 | GPU 索引构建/搜索失败时自动降级 CPU 暴力搜索；`requireGpu=true` 时改为直接报错 | 不保证 GPU 一定可用，可用性由 `RuntimeDetector` SPI 探测 |

### 1.3 规模

- Java 源文件 **5** 个：`VectorStorageProviderFactory`（SPI 工厂）、`CuvsVectorStorage`（GPU 实现）、`JvectorVectorStorageDelegate`（CPU 适配）、`VectorStorageProperties`（配置）、`VectorPerfBench`（性能基准）。
- SPI 注册文件 **1** 个：`META-INF/extensions/com.chua.common.support.vector.VectorStorageProvider`。
- 核心类 `@since 4.0.0.42`。

---

## 2. 核心 API 与速查表

本模块的日常入口只有一个：SPI 名 `vector`。链式构建器 `VectorStorageProvider.of("vector")` 来自 common-starter 的 `com.chua.common.support.vector.VectorStorageProvider`。

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建向量存储（自动选后端） | `VectorStorageProvider.of("vector").dimension(768).algorithm("cosine").properties(new VectorStorageProperties()).build()` | 有 GPU 用 cuVS，无 GPU 降级 jvector |
| 键值动态创建 | `VectorStorageProvider.createDynamic("vector", 768, VectorCompareAlgorithm.cosine(), Map.of("backend", "AUTO"))` | 配置来自业务侧持久化的键值对 |
| 强制 CPU | `new VectorStorageProperties().forceCpu(true)` | 跳过 GPU 探测，直接用 jvector |
| 强制 GPU | `new VectorStorageProperties().requireGpu(true)` | GPU 不可用直接抛异常，不降级 |
| 指定 GPU 索引类型 | `new VectorStorageProperties().indexType(VectorStorageProperties.CuvsIndexType.CAGRA)` | `CAGRA` / `BRUTE_FORCE` / `HNSW` |
| 写入 / 搜索 | `storage.add(id, float[])` / `storage.search(query, topK)` | 返回 `List<Vector>`，`metadata` 含 `score` |
| 删除 / 更新 / 清空 | `storage.remove(id)` / `storage.update(id, float[])` / `storage.clear()` | id 不存在时返回 `false` |
| 性能基准 | `VectorPerfBench`（`start-vector-perf.bat`） | 内存 / HYBRID / jvector 多组对比 |

传统风格与查询类入口（同样来自 `VectorStorageProvider`）：

```java
VectorStorage s1 = VectorStorageProvider.create("vector", 768,
        VectorCompareAlgorithm.cosine(), new VectorStorageProperties());
List<String> names = VectorStorageProvider.providers();   // 已注册的向量 SPI 名称
VectorStorageProvider p = VectorStorageProvider.find("vector"); // 未注册返回 null
```

---

## 3. 快速开始

```java
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.vector.support.configuration.VectorStorageProperties;

import java.util.List;

public class Demo {
    public static void main(String[] args) {
        // 自动检测（推荐）：有 GPU 用 cuVS，无 GPU 降级到 jvector
        VectorStorage storage = VectorStorageProvider.of("vector")
                .dimension(768)
                .algorithm("cosine")
                .properties(new VectorStorageProperties())
                .build();

        // 写入
        storage.add("doc-1", randomVector(768));
        storage.add("doc-2", randomVector(768));

        // 检索 topK（compare() 语义为相似度，越大越相似，结果按相似度降序）
        List<Vector> top10 = storage.search(randomVector(768), 10);
        top10.forEach(v -> System.out.println(v.id() + " -> " + v.metadata().get("score")));

        // 更新 / 删除 / 规模
        storage.update("doc-1", randomVector(768));
        storage.remove("doc-2");
        System.out.println(storage.size());

        storage.close();
    }

    static float[] randomVector(int dim) {
        float[] v = new float[dim];
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < dim; i++) {
            v[i] = (float) (rnd.nextDouble() * 2 - 1);
        }
        return v;
    }
}
```

`algorithm(String)` 支持内置算法名 `EUCLIDEAN` / `COSINE` / `DOT`（大小写不敏感）；也可传 `VectorCompareAlgorithm.cosine()` / `euclidean()` / `dotProduct()` 实例。`VectorStorage` 的完整契约（`add` / `search` / `size` / `remove` / `update` / `clear` / `close` / `dimension` 等）定义在 common-starter 的 `com.chua.common.support.vector.VectorStorage`。

---

## 4. 后端选择与降级规则

`VectorStorageProviderFactory`（SPI 名 `vector`，`@Spi(value = "vector", order = 50)`）按以下优先级解析最终后端：

| forceCpu | requireGpu | backend | 行为 |
| :---: | :---: | :---: | --- |
| `true` | 任意 | 任意 | 直接用 jvector（CPU），跳过 GPU 探测 |
| `false` | `false` | `CUVS` | 尝试 cuVS；不可用则降级 jvector 并记录日志 |
| `false` | `false` | `JVECTOR` | 直接用 jvector |
| `false` | `true` | 任意 | 选最优后端；GPU 不可用直接抛 `RuntimeException`（附驱动 / CUDA / cuVS 安装指南） |
| `false` | `false` | `AUTO`（默认） | 按 `RuntimeDetector` SPI 的 `priority()` 从高到低探测，取第一个 `isAvailable() == true` 的实现；无可用探测器时用 jvector |

补充规则（与源码一致）：

- `backend` 键值大小写不敏感，最终按 `Backend.valueOf(toUpperCase)` 解析；非法值抛 `IllegalArgumentException`。
- cuVS 存储创建过程抛出异常时：`requireGpu=false` 降级 jvector；`requireGpu=true` 抛 `RuntimeException("cuVS GPU 不可用且 requireGpu=true")`。
- `CuvsVectorStorage` 内部按 `indexType` 分三种策略：`CAGRA` / `HNSW`（GPU 图索引，懒构建——写入/删除/更新会使索引失效，下次搜索前重建）与 `BRUTE_FORCE`（纯内存精确搜索）。
- CAGRA / HNSW 搜索失败（含 GPU 异常）时自动降级 CPU 暴力搜索并 `warn`。
- GPU 粗筛取 `topK * 5` 个候选（广度 `searchEf`），再用自定义算法两阶段精排取 `topK`。
- 距离类型映射：`COSINE → CosineExpanded`；`DOT / DOT_PRODUCT / IP → InnerProduct`；其余（含未指定）`→ L2Expanded`。
- 所有公开操作（add / search / remove / update / size / clear / close）均为 `synchronized`。

---

## 5. 配置项

### 5.1 编程式配置 `VectorStorageProperties`

`com.chua.vector.support.configuration.VectorStorageProperties`，`@Accessors(chain = true, fluent = true)`，即 getter/setter 形如 `forceCpu()` / `forceCpu(true)`，可链式书写。

| 字段 | 类型 / 枚举 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `backend` | `Backend`：`CUVS` / `JVECTOR` / `AUTO` | `AUTO` | 后端类型；通常由 `forceCpu` / `requireGpu` 控制，显式设置可强制指定 |
| `forceCpu` | `boolean` | `false` | 强制 CPU（jvector），跳过 GPU 检测 |
| `requireGpu` | `boolean` | `false` | 要求 GPU 必须可用，否则抛异常（不降级） |
| `deviceId` | `int` | `0` | CUDA 设备标识 |
| `indexType` | `CuvsIndexType`：`CAGRA` / `BRUTE_FORCE` / `HNSW` | `CAGRA` | cuVS 索引类型 |
| `graphDegree` | `int` | `64` | CAGRA/HNSW 图度 |
| `intermediateGraphDegree` | `int` | `128` | CAGRA 中间图度 |
| `searchEf` | `int` | `100` | 搜索 exploration factor（搜索广度） |
| `bruteForceFetchFactor` | `int` | `3` | bruteforce 模式候选倍数（内部使用） |
| `jvectorMode` | `JvectorMode`：`MEMORY` / `ON_DISK` / `LARGER_THAN_MEMORY` | `MEMORY` | jvector 存储模式 |
| `jvectorGraphM` | `int` | `32` | jvector 图最大度数 M |
| `jvectorEfConstruction` | `int` | `100` | jvector 建图搜索深度 efConstruction |
| `jvectorIndexPath` | `String` | `./vector-index` | jvector 磁盘索引路径（`ON_DISK` / `LARGER_THAN_MEMORY` 模式） |

### 5.2 键值动态配置（`toProperties` 支持的键）

| 键 | 可选值 | 缺省 | 说明 |
| --- | --- | --- | --- |
| `backend` | `AUTO` / `CUVS` / `JVECTOR` | `AUTO` | 后端类型，大小写不敏感 |
| `forceCpu` | `true`/`1`/`on`/`yes`、`false`/`0`/`off`/`no` | `false` | 强制 CPU |
| `requireGpu` | 同上 | `false` | 必须有 GPU |

以上键与 `descriptor()` 暴露的表单字段一致（`VectorStorageField.select` / `VectorStorageField.bool`），供前端动态渲染配置表单。

### 5.3 Spring Boot 配置绑定

`VectorStorageProperties` 的 Javadoc 提及「或绑定到 Spring Boot `application.yml`」，但本模块源码**未提供** `@ConfigurationProperties` 前缀或自动装配文件；系统属性覆盖等机制亦**无**（源码未提供）。

---

## 6. SPI 扩展点

### 6.1 本模块注册的实现

`META-INF/extensions/com.chua.common.support.vector.VectorStorageProvider`：

```text
vector=com.chua.vector.support.spi.VectorStorageProviderFactory
```

即宿主引入本模块后，`VectorStorageProvider.of("vector")` / `createDynamic("vector", ...)` 即可用，无需额外注册。

### 6.2 后端探测扩展点：`RuntimeDetector`

后端自动探测通过 common-starter 的 `com.chua.common.support.vector.RuntimeDetector` SPI 完成，接口仅三个方法：

```java
String name();          // 如 "cuvs"
boolean isAvailable();  // 运行时是否可用
int priority();         // 默认值见接口定义；数值越大越先探测
```

- `VectorStorageProviderFactory` 用 `ServiceProvider.of(RuntimeDetector.class).collect()` 收集全部实现，按 `priority()` 降序取第一个可用者；`name() == "cuvs"` 映射为 GPU 后端，其余映射为 jvector。
- common-starter 内置 `CuvsRuntimeDetector`（`name = "cuvs"`）、`CudaRuntimeDetector` 等实现类；是否生效取决于其实际 SPI 注册与类路径环境。
- 想接入新的硬件后端：实现 `RuntimeDetector` 并按 SPI 约定注册，再在 `VectorStorageProviderFactory.create` 的 `switch` 中补充对应分支（当前仅 `CUVS` / `JVECTOR` 两分支，`default` 抛 `IllegalStateException`）。

---

## 7. 构建与依赖

### 7.1 依赖清单（`pom.xml`）

| 依赖 | 作用 |
| --- | --- |
| `com.chua:utils-support-common-starter` | `VectorStorageProvider` / `VectorStorage` / `RuntimeDetector` 等契约与工具 |
| `com.chua:utils-support-datasource-starter` | 数据源体系依赖（本模块所在 `utils-support-datasource-parent` 聚合下） |
| `com.chua:utils-support-jvector-starter`（`${project.version}`） | jvector CPU 实现 `JVectorVectorStorage`，作为无 GPU 降级 |

### 7.2 cuVS 运行时环境（可选，仅 GPU 后端需要）

cuVS Java API（Panama FFM 绑定）通过**反射**调用，无编译期依赖。运行时需安装（任选其一）：

```bash
# conda 安装
conda install -c rapidsai -c conda-forge libcuvs cuda-version=12.9

# 或从源码构建
git clone https://github.com/rapidsai/cuvs && cd cuvs/java && ./build.sh && mvn install
```

未安装时：`AUTO` 模式自动降级 jvector CPU；`requireGpu=true` 时启动创建即报错。

### 7.3 编译

```bash
mvn -o -q compile -DskipTests
```

---

## 8. 性能基准与脚本

`com.chua.vector.support.VectorPerfBench` 为内置性能基准（精简版），默认参数：维度 `DIM=128`、向量数 `COUNT=5000`、`TOP_K=10`、测量轮数 `MEASURE_ROUNDS=2`、每次测量查询数 `QS=20`、`FLUSH_BATCH=5000`。对比项：`MemoryVectorStorage`（cosine / euclidean / dot）、`DefaultVectorStorage` HYBRID 模式、`JVectorVectorStorage` MEMORY 模式（cosine / euclidean），输出写入速率（万条/s）与搜索延迟（µs/次、QPS）。

启动脚本（均启用 `jdk.incubator.vector` 模块）：

| 脚本 | 用法 | 说明 |
| --- | --- | --- |
| `start-vector-tests.sh` | `./start-vector-tests.sh VectorPerfBench` | Linux/macOS，按类名运行 `com.chua.vector.support.<类>` |
| `start-vector-tests.bat` | `start-vector-tests.bat VectorStarterTest` | Windows，默认主类 `VectorStarterTest` |
| `start-vector-perf.bat` | `start-vector-perf.bat` | 等价于 `start-vector-tests.bat VectorPerfBench` |

> 脚本按固定 Maven 仓库路径拼装 classpath（common-starter / datasource-starter / jvector-starter / jvector / guava / slf4j / agrona），版本以脚本内声明为准；脚本默认主类 `VectorStarterTest` 在本模块源码中**不存在**（源码未提供），请按需传入实际类名。

---

## 9. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `VectorStorage` / `AbstractVectorStorage` / `MemoryVectorStorage` / `VectorCompareAlgorithm` / `Vector` 契约 | `utils-support-common-starter`（`com.chua.common.support.vector`） |
| `RuntimeDetector` SPI 与 `CuvsRuntimeDetector` / `CudaRuntimeDetector` 探测器 | `utils-support-common-starter` |
| jvector 具体索引实现 `JVectorVectorStorage` / `JVectorStorageProperties` | `utils-support-jvector-starter`（`com.chua.jvector.support.*`） |
| Milvus 等其它向量库接入 | 对应独立 starter（如 `utils-support-milvus-starter`） |
| NVIDIA 驱动 / CUDA / cuVS 原生库 | 运行时外部环境，需按第 7 节自行安装 |
| Spring Boot 自动装配、HTTP 管理端点、数据库表与 Mapper | 本模块均**无**（源码未提供） |
