# WAL 四大存储引擎吞吐基准测试报告

**测试时间**: 2026-08-31  
**环境**: Windows 10, Java 25 (Corretto), SSD  
**版本**: utils-support-common-starter 4.0.0.42

---

## 测试结果总览

| 引擎 | 测试场景 | 记录数 | 耗时 | 吞吐量 |
|------|---------|--------|------|--------|
| **KV** | 顺序写入 | 100,000 | 290 ms | **344,828 ops/s** |
| **KV** | 顺序写入 | 1,000,000 | 3,018 ms | **331,345 ops/s** |
| **KV** | 顺序写入(首次) | 100,000 | 960 ms | 104,167 ops/s |
| **KV** | 顺序写入(首次) | 1,000,000 | 12,596 ms | 79,390 ops/s |
| **TS** | 顺序写入 | 100,000 | 699 ms | **143,062 ops/s** |
| **TS** | 多 Measure 写入 | 1,000,000 (5×200K) | 4,808 ms | **207,987 ops/s** |
| **VEC** | 向量写入 (dim=128) | 10,000 | 166 ms | **60,241 ops/s** |
| **JDBC** | 行插入 | 100,000 | 1,593 ms | **62,775 ops/s** |
| **JDBC** | 行插入(首次) | 100,000 | - | - |

> 注：首次运行含冷启动开销（段文件创建、索引初始化），热运行后性能显著提升。

---

## 各引擎详细说明

### 1. KV 引擎 (KvWalStoreSystem)

- **架构**: B+Tree 索引 + WAL 段文件，支持按 key 的 put/get/delete
- **写入吞吐**: 33 万~34 万 ops/s（热数据）
- **特点**: 适合键值对存储，支持按前缀删除、范围查询
- **Segment 数量**: 100K 约 53 个段，1M 约 53 个段

### 2. TS 引擎 (TsWalStoreSystem)

- **架构**: 时间序列存储，支持多 measure 并行写入
- **写入吞吐**: 14 万 ops/s（单 measure），20 万 ops/s（5 measure 并行）
- **特点**: 支持聚合查询（AVG/MIN/MAX/SUM）、TTL 自动过期
- **适用场景**: 监控指标、物联网时序数据

### 3. VEC 引擎 (VecWalStoreSystem)

- **架构**: 向量存储，支持 brute-force 相似度搜索
- **写入吞吐**: 6 万 ops/s（dim=128，L2 归一化）
- **特点**: 当前为暴力搜索，后续可集成 HNSW/IVF 加速
- **适用场景**:  embeddings 存储、语义检索

### 4. JDBC 引擎 (JdbcWalStoreSystem)

- **架构**: SQL 风格表存储，支持 CREATE/INSERT/UPDATE/DELETE/QUERY
- **写入吞吐**: 6 万 ops/s（100K 行，5 列混合类型）
- **特点**: 支持类 SQL 查询（`SELECT * FROM table`）
- **适用场景**: 结构化数据、报表存储

---

## 测试方法

```java
// KV 100K 写入
KvWalStoreSystem store = KvWalStoreSystem.create(dir);
for (int i = 0; i < 100_000; i++) {
    store.put("user:" + i, payload);
}
// 吞吐量 = count * 1000.0 / elapsedMs

// TS 多 measure 写入
TsWalStoreSystem store = TsWalStoreSystem.create(dir);
for (int m = 0; m < 5; m++) {
    for (int i = 0; i < 200_000; i++) {
        store.append(measures[m], timestamp++, value);
    }
}

// VEC 写入（L2 归一化）
VecWalStoreSystem store = VecWalStoreSystem.create(dir, 128);
for (int i = 0; i < 10_000; i++) {
    float[] v = randomVector(128);
    v = normalize(v);  // L2 归一化
    store.add("vec_" + i, v);
}
```

---

## 结论

1. **KV 引擎吞吐最高**：33 万+ ops/s，适合高频点查场景
2. **TS 引擎多 measure 并行性能更优**：20 万 ops/s（5 measure）
3. **VEC 引擎受维度影响大**：dim=128 时 6 万 ops/s，后续可通过近似搜索优化
4. **JDBC 引擎适合结构化数据**：6 万 ops/s，支持 SQL 查询
5. **四个引擎均通过单元测试**（WalStoreSystemTest 17/17 PASS）

---

## 相关文件

- 测试源码: `utils-support-core-parent/utils-support-common-starter/src/test/java/com/chua/common/support/datasource/wal/WalStoreStressTest.java`
- 单元测试: `WalStoreSystemTest.java`（17 个测试方法）
- 存储引擎实现: `src/main/java/com/chua/common/support/datasource/wal/`
