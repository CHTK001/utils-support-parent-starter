# VectorStorage 示例单元测试能力矩阵

> 针对 VectorStorage SPI 实现的示例与测试覆盖，通过 VectorStorageProvider.of()/create() 切换后端，
> 单入口覆盖所有实现的通用能力。当前 live 示例：VectorStorageChainExample（14 用例）+ JVectorModeSwitchExample（3 模式）。

## 1. 基础能力矩阵

| 能力 \ 实现 | MEMORY | JVECTOR-MEMORY | JVECTOR-ON_DISK | JVECTOR-LTM | MILVUS |
|:----------:|:------:|:------:|:------:|:------:|:------:|
| add(String, float[]) | ✅ | ✅ | ✅ | ✅ | ❌ 未启用 |
| add(Vector) 重载 | ⏳<sup>3</sup> | ⏳<sup>3</sup> | ⏳<sup>3</sup> | ⏳<sup>3</sup> | ❌ |
| search(float[], int) | ✅ | ✅ | ✅ | ✅ | ❌ |
| search(Vector, int) 重载 | ⏳<sup>3</sup> | ⏳<sup>3</sup> | ⏳<sup>3</sup> | ⏳<sup>3</sup> | ❌ |
| size() | ✅ | ✅ | ✅ | ✅ | ❌ |
| clear() | ✅ | ✅ | ✅ | ✅ | ❌ |
| close() | ✅ | ✅ | ✅ | ✅ | ❌ |
| remove(id) | ✅ | ✅ | ✅ | ✅ | ⏳<sup>4</sup> 已实现未实测 |
| update(id, vector) | ✅ | ✅ | ✅ | ✅ | ⏳<sup>4</sup> 已实现未实测 |
| 重复 id 去重 | ✅<sup>1</sup> | ✅<sup>1</sup> | ✅<sup>1</sup> | ✅<sup>1</sup> | ❌ |
| providers() SPI 列举 | ✅<sup>2</sup> | ✅<sup>2</sup> | ✅<sup>2</sup> | ✅<sup>2</sup> | ❌ |
| 维度校验 | ✅ | ✅ | ✅ | ✅ | ⏳<sup>4</sup> 已实现未实测 |
| 关闭后访问校验 | ⏳ 待补 | ⏳ 待补 | ⏳ 待补 | ⏳ 待补 | ❌ |

## 2. 高级能力矩阵

| 能力 \ 实现 | MEMORY | JVECTOR-MEMORY | JVECTOR-ON_DISK | JVECTOR-LTM | MILVUS |
|:----------:|:------:|:------:|:------:|:------:|:------:|
| 内置算法 EUCLIDEAN | ✅ | ✅ | ✅ | ✅ | ❌ |
| 内置算法 COSINE | ✅ | ✅ | ✅ | ✅ | ❌ |
| 内置算法 DOT | ⏳ 待补 | ⏳ 待补 | ⏳ 待补 | ⏳ 待补 | ❌ |
| 自定义 VectorCompareAlgorithm | ✅ | ✅ | ✅ | ✅ | ❌ |
| 链式 Builder（of + dimension + algorithm + properties + build） | ✅ | ✅ | ✅ | ✅ | ❌ |
| create() 与 of().build() 等价 | ✅ | - | - | - | ❌ |
| HNSW 图索引 | ❌ | ✅ | ✅ | ✅ | ❌ |
| 磁盘持久化（ON_DISK 落盘 + 文件存在校验） | ❌ | ❌ | ✅ | ❌ | ❌ |
| PQ 压缩（LTM 两阶段搜索） | ❌ | ❌ | ❌ | ✅ | ❌ |
| 无效 SPI 名抛异常 | ✅ | - | - | - | - |
| 无效算法名抛异常 | ✅ | - | - | - | - |
| 远程向量数据库 | ❌ | ❌ | ❌ | ❌ | ✅ 未启用 |

## 3. 执行方式

```bash
# 运行链式 API 综合自测（14 用例）
java -cp ... com.chua.example.vector.VectorStorageChainExample

# 运行 jvector 三模式切换验证
java -cp ... com.chua.example.vector.JVectorModeSwitchExample

# 预期输出（2026-07-31 纯 .m2 classpath 实测）
# VectorStorageChainExample → 结果: 14 通过, 0 失败
# JVectorModeSwitchExample → 三模式 Top-1 均 id=id-5, score=0.978
```

## 4. 实现说明

| 实现 | of() 值 | 说明 |
|------|:--------:|------|
| MemoryVectorStorage | memory | 基于内存的内存实现，默认可用 |
| JVectorVectorStorage | jvector | 基于 JVector 4.0.0-rc.9 HNSW 图索引，支持 MEMORY/ON_DISK/LARGER_THAN_MEMORY 三模式 |
| MilvusVectorStorage | milvus | 基于 Milvus SDK 的远程向量数据库，需 milvus 服务（remove/update 已实现：DeleteReq 按主键删除 + QueryReq 存在性校验 + UpsertReq 覆盖更新） |

## 5. 总结

| 维度 | 状态 |
|------|:----:|
| 基础向量 CRUD（String/float[] 版） | 5/5 实现（4/5 实测 ✅，Milvus 已实现待实测<sup>4</sup>） |
| add(Vector)/search(Vector) 重载 | ⏳ 待补（接口默认方法，见 G-06） |
| 搜索与排序（Top-1/Top-K） | 4/4 实现 ✅ |
| remove / update | 5/5 实现（4/5 实测 ✅，Milvus 已实现待实测<sup>4</sup>） |
| 内置算法支持（EUCLIDEAN/COSINE） | 4/4 实现 ✅ |
| 自定义算法 | 4/4 实现 ✅ |
| 资源生命周期（close） | 4/4 实现 ✅ |
| 重复 id 去重（AbstractIdOrdinalStorage 统一） | 4/4 实现 ✅ |
| 维度校验 | 5/5 实现（4/5 实测 ✅，Milvus 已实现待实测<sup>4</sup>，add/update 均校验） |
| 关闭后访问校验 | ⏳ 全部待补 |
| DOT 算法 | ⏳ 待补 |
| MILVUS 后端 | ❌ 未启用（需外部服务） |

> ✅ = 已测试通过  │  ⏳ = 待补充（⏳⁴ = 已实现未实测，需真实 Milvus 服务）  │  ❌ = 不支持/未启用（未启用 = 实现存在但未被 live 示例执行，非未实现）  │  (空白) = 待验证
>
> <sup>1</sup> 去重已由 common-starter 新增的 AbstractIdOrdinalStorage 抽象类统一实现（id↔序数映射 + tryRegister 去重守卫 + swap-remove），memory/jvector 三策略复用，行为一致（重复 id 返回 false），链式示例 14 用例实测通过（对应覆盖矩阵 G-03 已闭环）。
> <sup>2</sup> providers() 是 VectorStorageProvider 的静态全局能力（列举全部已注册 SPI，含 name()），并非各实现独立行为；由 JVectorModeSwitchExample 的 `[1] 已注册的向量存储实现` 块实际调用验证。
> <sup>3</sup> add(Vector)/search(Vector,int) 是接口默认方法（委托给 String/float[] 版本），示例未直接调用验证，故标 ⏳（对应覆盖矩阵 G-06）。
> <sup>4</sup> Milvus remove/update 已于 2026-07-31 实现（milvus-starter BUILD SUCCESS，javap 确认 remove/update 方法在位），但 live 验证需真实 Milvus 服务，当前无可用实例故未实测。
