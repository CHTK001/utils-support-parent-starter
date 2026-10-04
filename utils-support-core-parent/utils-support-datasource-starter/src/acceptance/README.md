# 引擎验收 harness（Engine / KvEngine / SeriesEngine）

本目录是对 `utils-support-*` 各数据引擎的**可复跑验收程序**，不是单元测试框架：
每个程序自带断言、自行写出 UTF-8 报告，并以**进程退出码**表达结果
（`0` = 全部断言通过，非 `0` = 通过数即失败数之和）。

## 为什么放在 `src/acceptance/`

- `src/acceptance/` **不是** Maven 的标准源码根（`src/main/java`），构建时完全忽略，
  因此不污染任何模块产物；沿用 `spring-support-monitor-starter/src/acceptance/` 的既有约定。
- harness 需要 **360 余个 jar** 的完整运行期 classpath，无法用模块自身的依赖声明表达，
  故自带 `pom.xml` 仅用于解析 classpath（`dependency:build-classpath`），不参与 reactor 构建。
- 历史上这类工具只留在 `.tmp/`，没有版本控制，随会话结束即丢失。**工具必须入库。**

## 四阶段与覆盖范围

| 阶段 | 程序 | 断言数 | 覆盖内容 |
|---|---|---|---|
| 一 | `EngineAcceptance` | 288 | 24 个 SPI 注册引擎的**契约面**：`name/url/dialect/dataSource 注册/能力开关/生命周期/异常语义` |
| 二 | `EngineFunctional` | 132 | 5 个**嵌入式 SQL 引擎**（memory / file / h2 / sqlite / duckdb）的深度功能面：DDL、DML、条件、分页、聚合、类型化查询、事务边界 |
| 三 | `EngineSeriesKv` | 75 | `KvEngine` 实现 ×3、`SeriesEngine` 实现 ×2，外加 **mysql**（真实 3306）的 SQL 面 |
| 四 | `EngineEmbedded` | 24 | 嵌入式**非 SQL** 引擎：parquet / lucene / nitrite / tablesaw，以及 rocksdb 的文档与全文面 |

合计 **519 条断言**。

### 契约断言的来源

断言**只取自接口 Javadoc 的显式契约**，并优先覆盖「文档写了、实现容易漏」的分支，例如：

- `removeDataSource(null)` 必须抛 `IllegalArgumentException`，不得静默返回；
- `querySql(null)` 必须是 `IllegalArgumentException`，不得是裸 `NullPointerException`；
- 无 `WHERE` 的 `update` / `remove` 必须被拒绝，不得更新或清空整表；
- `SeriesEngine.series(metricName=null)` 在指标不唯一时必须抛 `IllegalArgumentException`；
- `purge` 后 `metrics()` 不得再报告已被删除的指标。

### 能力适配，而非硬编码期望

各引擎能力面不同，断言按其**自身声明**适配：

- `DataSourceSeriesEngine` 是「一表一指标」，多指标断言自动降级为「不适用」；
- `ParquetEngine.supportsSql()==false`，读路径是 `executeNewQuery`（Lambda/类型化面），
  因此用 `query(Doc.class).list()` 断言，**不用** `querySql`；
- `TablesawEngine` 只依赖 `utils-support-common-starter`，读不到
  `com.chua.datasource.support.annotation.TableName`，表名一律由类名派生
  （`Doc` → `doc`），这是该引擎的**设计边界**，不是缺陷。

## 运行

```bat
cd utils-support-core-parent\utils-support-datasource-starter\src\acceptance
run-acceptance.bat            :: 四阶段全跑
run-acceptance.bat 1 4        :: 只跑阶段一与阶段四
```

前置条件：

- **pwsh 7** 在 PATH 上（仓库禁用 PowerShell 5.1：它按 ANSI 解码无 BOM 的 UTF-8 脚本，
  会打乱非 ASCII 内容）。`run-acceptance.bat` 会先检查。
- JDK 21+（`javac` / `java` 在 PATH 上）。
- 阶段三的 mysql 套件需要本机 `127.0.0.1:3306` 的 MySQL（`root/root`）与库 `engine_accept`；
  redis 套件需要 `127.0.0.1:6379`。未启动时该套件会失败，其余阶段不受影响。
- `prepare-classpath.ps1` 会从 `pom.xml` 解析 classpath，并在本目录生成 `lib\`
  （硬链接，失败则复制）与 `classes\`。这两者都是**生成物，不入库**。

## 产物与交付纪律

程序自行写出 UTF-8 报告（`*-report.txt`、`EngineAcceptance` 无报告文件但同样在 stdout 打印汇总）。
报告里的断言数必须与退出码一致；**退出码比报告可信**——曾出现过程序在写出报告前被外部因素
终止、留下上一轮陈旧报告的情形（本轮阶段三遇过一次不可复现的偶发静默退出，两次复跑均正常）。

本目录的源码是**验收工具**，不是产品代码：修复产品缺陷请在模块 `src/main/java` 内进行，
并用本 harness 回归。本轮由 harness 暴露并修复的产品缺陷清单、产物 md5 绑定与
「查证后判定为非缺陷」的两处说明，见同目录 `DELIVERY-engine.md`。
