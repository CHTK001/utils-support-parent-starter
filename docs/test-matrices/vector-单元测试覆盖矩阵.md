# VectorStorageExample 单元测试覆盖矩阵

## 版本信息
- 类名：VectorStorageExample
- 模块：utils-support-example-starter
- 包路径：com.chua.example.vector
- 作者：CH
- 更新日期：2026-08-01

## SPI 实现覆盖矩阵

| 实现类型 | --mode | add | search | update | remove | clear | size | 磁盘文件 | 持久化往返 | PQ 压缩 | 状态 |
|:--------|:-------|:---:|:------:|:------:|:------:|:-----:|:----:|:--------|:----------|:-------:|:----|
| jvector | MEMORY               | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | -      | ✗        | ✗     | 通过 |
| jvector | ON_DISK              | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅     | ✅       | ✗     | 通过 |
| jvector | LARGER_THAN_MEMORY   | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | -      | ✗        | ✅    | 通过 |

## 存储模型对比

| 模式 | 图存储 | 向量存储 | 内存占用 | 适用场景 |
|:-----|:------|:--------|:--------|:--------|
| MEMORY              | 内存图      | 内存（全精度 float[]）             | 最大     | 小数据集 / 测试 / 一次性批处理 |
| ON_DISK             | 磁盘 mmap   | 内存（全精度 float[]）+ `.vectors` 备份 | 中等     | 中等规模 / 需跨进程持久化 |
| LARGER_THAN_MEMORY  | 内存图      | 内存（全精度 + PQ 压缩副本）       | 较小     | 中等规模 / 查询性能敏感 |

## 测试场景覆盖矩阵

| 场景ID | 测试方法 | 模式 | 入参 | 断言 | 通过条件 |
|:------|:--------|:-----|:-----|:-----|:--------|
| TC-11 | testJVectorAddSearch | MEMORY / ON_DISK / LARGER_THAN_MEMORY | seed 10 个随机向量 + 一次 search | Top-1 命中第一个种子向量 | results.get(0).id() 等于 "jv-addsearch-0" |
| TC-12 | testJVectorUpdate    | MEMORY / ON_DISK / LARGER_THAN_MEMORY | add a,b,c + update a=v4 | size 不变 + search v4 返回 a | size==3 && 更新后 Top-1 为 a |
| TC-13 | testJVectorRemove    | MEMORY / ON_DISK / LARGER_THAN_MEMORY | add a,b,c + remove a | size=2 + remove 不存在的 id 返回 false | size==2 && remove("missing")==false |
| TC-14 | testJVectorClear     | MEMORY / ON_DISK / LARGER_THAN_MEMORY | add a,b + clear | size=0 | size==0 |
| TC-15 | testJVectorSize      | MEMORY / ON_DISK / LARGER_THAN_MEMORY | add a,b,c | size=3 | size==3 |
| TC-16 | testJVectorDiskFile  | ON_DISK | seed 10 + close | 磁盘图文件存在 | Files.exists(indexPath)==true |
| TC-17 | testJVectorPersistence | ON_DISK | add 5 随机 + target + close → 同路径重开 | size=6 + search target 命中 | 重启 size==6 && search Top-1=="target" |

## 执行记录

| 日期 | 实现类型 | --mode | 结果 | 备注 |
|:-----|:--------|:-------|:-----|:-----|
| 2026-08-01 | jvector | MEMORY | PASS (5/5) | TC-11~15 全部通过 |
| 2026-08-01 | jvector | ON_DISK | PASS (7/7) | TC-11~17 全部通过，含磁盘图文件 + 持久化往返 |
| 2026-08-01 | jvector | LARGER_THAN_MEMORY | PASS (5/5) | TC-11~15 全部通过；PQ 压缩生效 |

## 已知约束

- `--type memory` 走 MemoryVectorStorage，能力矩阵一致但不在本示例验证范围内。
- LARGER_THAN_MEMORY 因种子向量仅 10 个，PQ 训练时 jvector 会提示"Using less than 256 PQ clusters will not reduce the memory footprint"，属于 jvector 自身的告警，不影响功能。
- DiskStrategy 的备份向量文件使用 `.vectors` 后缀，与 jvector 内部的图文件（写入 `indexPath`）互不冲突。
- 当前所有模式**单实例**持有数据；如需多副本分布式部署，需外挂外部存储协调。
- 三种模式**单进程内**均受 JVM 堆内存约束；超大规模场景（亿级以上向量、内存不足）建议引入分片 + 外部向量数据库（Milvus / Qdrant / Vespa 等）。
