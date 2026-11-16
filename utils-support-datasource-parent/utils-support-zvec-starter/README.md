# Zvec Starter

向量检索 SPI（`com.chua.common.support.vector.VectorStorageProvider`）的 Zvec 实现。

Zvec 是阿里巴巴开源的嵌入式向量数据库（C++ 内核 + JNI 绑定），一个目录即一个集合，无需部署外部服务；
本模块通过官方 `org.zvec:zvec-java` 绑定接入，对外只暴露统一的 `VectorStorage` 能力。

## 依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-zvec-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

`zvec-java:0.8.0` 主 JAR 内置 windows-x86_64 / linux-x86_64 / linux-arm64 / macosx-arm64 /
macosx-x86_64 原生库，运行时按平台自动解压加载，不需要另装向量库。

> 传递依赖 `org.bytedeco:javacpp:1.5.14`。宿主若已引入其他 bytedeco 组件（OpenCV 等），
> 需确认版本仲裁结果不低于 1.5.14，否则原生库加载会失败。

## SPI 名称

`zvec`（SPI 注册键统一大写为 `ZVEC`，查找大小写不敏感）。

## 配置项

由 `ZvecVectorStorageProvider.descriptor()` 声明，前端可据此动态渲染表单：

| 键 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `path` | PATH | `./zvec-index` | 集合目录；一个目录一个集合，必填 |
| `hnswM` | NUMBER | `16` | HNSW 最大度数 M |
| `efConstruction` | NUMBER | `200` | 建图候选集深度 |
| `queryEf` | NUMBER | `64` | 查询候选集深度，实际取值不小于 topK |
| `enableMmap` | BOOLEAN | `false` | 集合读取走 mmap |
| `flushOnWrite` | BOOLEAN | `false` | 每次写入立即 flush |

## 使用

```java
Map<String, Object> config = new HashMap<>();
config.put("path", "/data/vector/kb-1");
config.put("hnswM", "16");

try (VectorStorage storage = VectorStorageProvider.createDynamic(
        "zvec", 1024, VectorCompareAlgorithm.cosine(), config)) {
    storage.add("doc-1", embedding);
    List<Vector> hits = storage.search(query, 5);
}
```

也可用链式入口：`VectorStorageProvider.of("zvec").dimension(1024).algorithm("COSINE").build()`
（该入口不读取键值配置，集合目录取默认值）。

## 语义与实现约定

- `add` 对已存在的主键返回 `false`（与内存、JVector 实现一致），不覆盖。
- `update` / `remove` 对不存在的主键返回 `false`。
- `removeByIdPrefix` 通过快照迭代器取主键后批量删除，不依赖 zvec 的过滤表达式语法。
- `search` 返回的 `metadata.score` 统一用当前比较算法重算并按相似度降序排列：
  zvec 原生 score 随度量变化（COSINE 为相似度、L2 为距离），直接透出会让上层排序口径不一致。
- `size()` 取 `CollectionStats.docCount`，未 flush 的写入同样计入（已实测）。
- `clear()` 销毁集合目录后按当前 schema 重建；`rebuild()` = `flush` + `optimize`。
- `close()` 会先 `flush` 再释放；由本模块初始化的 zvec 原生库在最后一个实例关闭时 `shutdown`，
  宿主若已自行初始化则不接管。
- **维度一致性**：重新打开已有集合时，若向量维度与当前 embedding 不一致，抛
  `IllegalStateException` 并提示目录与维度，不静默删除历史数据。
- **目录约定**：`createAndOpen` 要求路径不存在，因此目录不存在或为空目录时新建集合；
  空目录会被删除后按新集合创建（空目录不含数据）。

## 验证记录

2026-11-16，Windows x86_64 + JDK 25 + zvec-java 0.8.0（本机实跑，非推断）：

- `mvn -o -B -DskipTests compile` 通过；SPI 发现结果为 `[ZVEC, MEMORY]`。
- 增删改查全链路通过：重复 `add` 返回 `false`、`size` 正确、topK 按余弦相似度降序、
  `update`/`remove` 对缺失主键返回 `false`、前缀删除计数正确、`clear` 归零。
- 持久化：写入后 `close()`，重新打开同目录 `size` 与检索结果一致。
- 维度不一致：以 4 维打开 8 维集合，按预期抛 `IllegalStateException`。
- 写入耗时（128 维、200 条）：不 flush 约 58ms；每条 flush 约 4158ms（约 20ms/次），
  这是 `flushOnWrite` 默认关闭的依据。
