# 引擎验收与修复交付报告（Engine / KvEngine / SeriesEngine 全子类）

日期：2026-10-04
仓库：`utils-support-parent-starter`
已提交的产品修复：`a22f42be1`（11 文件，+346 / −38）
验收结论：**四阶段共 519 条断言全绿，0 失败**

---

## 1. 被验产物与 md5（**换包即失效，须重跑后才能引用本报告**）

| 产物 | md5（前 8 位） | 大小 | 本批是否重建 |
|---|---|---|---|
| `com.chua:utils-support-common-starter:4.0.0.42` | `2d8648b1` | 4,563,385 B | 是（缺陷 1/3/4/5/6/7 所在） |
| `com.chua:utils-support-datasource-starter:4.0.0.42` | `d1fed079` | 342,996 B | 是（缺陷 1/2/3/4/6/7 所在） |
| `com.chua:utils-support-redis-starter:4.0.0.42` | `4f50d47b` | 125,768 B | 是（缺陷 8/9 所在） |
| `com.chua:utils-support-h2-starter:4.0.0.42` | `ba65bcc3` | 29,453 B | 否（阶段二负载） |
| `com.chua:utils-support-mysql-starter:4.0.0.42` | `dff38075` | 140,011 B | 否（阶段三负载） |
| `com.chua:utils-support-rocksdb-starter:4.0.0.42` | `d1f43707` | 31,213 B | 否（阶段三/四负载） |
| `com.chua:utils-support-tablesaw-starter:4.0.0.42` | `293d527f` | 39,346 B | 否（阶段四负载） |
| `com.chua:utils-support-parquet-starter:4.0.0.42` | `96db579b` | 21,193 B | 否（阶段四负载） |
| `com.chua:utils-support-lucene-starter:4.0.0.42` | `b33fa46c` | 35,450 B | 否（阶段四负载） |
| `com.chua:utils-support-nitrite-starter:4.0.0.42` | `e6fdd83a` | 12,584 B | 否（阶段四负载） |

签收前须重新核对上述 md5；不一致即本报告作废。

## 2. 验收四阶段

| 阶段 | 范围 | 断言 | 结果 |
|---|---|---|---|
| 一 | **全部 24 个已注册 Engine**（SPI 创建 / 能力契约 / 生命周期 / 空参与边界） | 288 | 全绿 |
| 二 | **5 个嵌入式 SQL 引擎**（memory / file / h2 / sqlite / duckdb）深度功能面：建表·参数化写入·类型化查询·标量查询·分页·批量·Lambda 三件套·多数据源·错误语义 | 132 | 全绿 |
| 三 | **KvEngine 3 个实现**（ChronicleMapKv / RocksDbEngine / RedisClient）+ **SeriesEngine 2 个实现**（DataSourceSeriesEngine / RedisSeriesEngine）+ **mysql**（本机 3306） | 75 | 全绿 |
| 四 | **嵌入式非 SQL 引擎**：parquet（列式文件读写回）、lucene（建索引·全文检索·限额·无命中·删索引）、nitrite（文档 CRUD + findAll）、tablesaw（CSV 装载·表枚举·Lambda 过滤）、rocksdb（文档 CRUD） | 24 | 全绿 |

引擎清单（SPI 权威来源 `META-INF/extensions/...datasource.engine.Engine`）：
memory、file、h2、sqlite、duckdb、parquet、lucene、rocksdb、nitrite、tablesaw、
mysql、postgresql、sqlserver、sqlserver-legacy、oracle、redis、elasticsearch、solr、
neo4j、hbase、influxdb、clickhouse、greptimedb、prometheus。

## 3. 修复的 9 处真实缺陷

| # | 缺陷 | 根因 | 影响 |
|---|---|---|---|
| 1 | **全部 JDBC 引擎 `Lambda page()` 恒失败** | `AbstractEngine.executePage` 在 `finally` 里 `wrapper.limit(origLimit)`，而 origLimit 通常为 0；`limit(int)` 对 0 抛 IAE，**在 finally 中抛出即覆盖已成功的分页结果** | 分页功能完全不可用 |
| 2 | memory/file 类型化查询不可用 | 未覆写 `querySql(ql, Class)`，接口默认实现按 `getExecutor()==null` 抛 UOE，与 `supportsSql()==true` 冲突 | 能力声明与行为不一致 |
| 3 | **memory 无 WHERE 的 `update()`/`remove()` 静默作用于全表** | 引擎求值器绕过 `buildSql()` 里的全表守卫；空条件谓词恒真 → 更新整表；`negate()` 后恒假 → **清空整表** | 静默数据破坏 |
| 4 | memory/file `MAX(age)` 等标量聚合报错 | 解析器只字面支持 `COUNT(*)`；而接口 Javadoc 明确把 `SELECT max(id)` 列为 `queryValue` 适用场景 | 文档承诺的功能缺失 |
| 5 | `removeDataSource(null)` 静默无操作 | 实现与接口契约「名称为 null 时抛 IAE」相悖 | 拼错名称伪装成「已摘除」 |
| 6 | memory/file `querySql(null)` 抛裸 NPE | 缺非空校验 | 无法区分用法错误与内部缺陷 |
| 7 | **memory/file 类型化查询把全部非 String 字段映射为 null** | `setValue` 的 `guessType` 把 Integer/Long 推断成 `int.class`/`long.class`，而 Lombok setter 形参是包装类型 → 形参不匹配 → 异常被吞 | 读路径静默数据丢失 |
| 8 | Redis Series 把契约要求的 IAE 包成 ISE | `series()` 宽 catch 吞掉 `resolveSingleMetric` 抛出的 IAE | 调用方 `catch(IAE)` 失效，参数错误显示成服务异常 |
| 9 | Redis Series `purge()` 后该 monitor 永久不可查 | 删了 ZSET 数据却未摘除指标索引 → `metrics()` 仍报「已写入」→ `series(metricName=null)` 永久抛「指标不唯一」 | 清理即自锁 |

## 4. 查证后**判定为非缺陷**的两处（避免后人误"修"）

1. **`ParquetEngine` 不支持 `querySql`** —— 其 `supportsSql()==false`，读路径是
   `AbstractEngine.executeNewQuery`（Lambda / 类型化查询面）。能力声明与行为**一致**，
   不是缺陷；用 `query(Doc.class).list()` 读取即正常。
2. **`TablesawEngine` 不认 `@TableName`** —— 该模块（`utils-support-tablesaw-starter`）
   只依赖 `utils-support-common-starter`，**不依赖** `utils-support-datasource-starter`，
   因此在构造上读不到 `com.chua.datasource.support.annotation.TableName`。
   表名一律由类名派生（`Doc` → `doc`）是该引擎的**模块边界**，不是漏实现。
   已在 `TablesawEngine.getTableName` 处加注释记录；若将来要与 JDBC/内存引擎共用注解约定，
   须先评估引入 `datasource-starter` 依赖对传递依赖面的影响，**不要顺手加**。

## 5. 复跑方式

harness **已入库**（不再只留 `.tmp`，那里没有版本控制）：

```
utils-support-core-parent/utils-support-datasource-starter/src/acceptance/
├─ run-acceptance.bat          四阶段运行器（自动解析 classpath → 编译 → 逐阶段执行）
├─ prepare-classpath.ps1       从 pom.xml 解析 classpath 并生成 lib\（硬链接/复制）
├─ pom.xml                     仅用于解析 classpath，不参与 reactor 构建
├─ harness/*.java              四个验收程序
└─ README.md                   覆盖范围、前置条件、交付纪律
```

```bat
cd D:\ch\project\utils-support-parent-starter\utils-support-core-parent\utils-support-datasource-starter\src\acceptance
run-acceptance.bat              :: 四阶段全跑，末行 ACCEPTANCE_RESULT=PASS/FAIL
run-acceptance.bat 1 4          :: 只跑阶段一与阶段四
```

前置条件：

- **pwsh 7** 在 PATH 上（仓库禁用 PowerShell 5.1：它按 ANSI 解码无 BOM 的 UTF-8 脚本）。
- JDK 21+。
- 阶段三 mysql 需本机 `127.0.0.1:3306`（`root/root`）与库 `engine_accept`；
  redis 需 `127.0.0.1:6379`。缺服务时仅阶段三失败，其余不受影响。
- 打开被验模块的**新构建**：`src/acceptance/pom.xml` 引用的是本地仓库制品，
  改完产品代码必须先 `mvn install` 对应模块，否则验的是旧包（**报告 md5 就是为此存在**）。

## 6. 尚未覆盖（如实声明）

1. **需外部服务的 11 个引擎**（postgresql、sqlserver、oracle、clickhouse、hbase、solr、
   neo4j、elasticsearch、influxdb、greptimedb、prometheus）只有契约面；
   本机无对应服务，无法做真实读写验收。**这部分是"未验"，不是"已通过"。**
2. `RedisKvEngine`（需 Spring `StringRedisTemplate`）、`InfluxdbSeriesEngine`（需 InfluxDB）、
   `NoopSeriesEngine`（monitor 模块私有静态类）未纳入。
3. `SeriesEngine.purge` 契约存在**自身歧义**：`0` 既被定义为「后端不支持」，又是「成功但删了 0 条」
   的自然返回值，调用方无法区分。本报告未改接口语义，仅记录。
4. **UI 增强尚未开始**（目标中的「ui增强」）。
