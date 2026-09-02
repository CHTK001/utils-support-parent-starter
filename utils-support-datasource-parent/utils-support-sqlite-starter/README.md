# utils-support-sqlite-starter

SQLite 嵌入式数据库支持，提供同步引擎 + 响应式引擎（JDBC 路径 + Reactor Flux/Mono）。

---

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-sqlite-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `SqliteEngine` | SQLite 同步引擎，封装 HikariCP + sqlite-jdbc（SPI: `sqlite`） |
| `SqliteReactorEngine` | SQLite 响应式引擎：`boundedElastic` 线程池执行 JDBC 操作，对外暴露 `Flux`/`Mono`，内部委托同步 JDBC 数据源；SQLite 无 R2DBC 驱动时自动降级到 JDBC 路径 |
| `SqlitePolledDirectory` | 数据库轮询目录实现 |

---

## 使用示例

```java
// 同步引擎
SqliteEngine e = new SqliteEngine();
e.addDataSource("default", "data/app.db");
e.getExecutor().execute("CREATE TABLE IF NOT EXISTS t(id INTEGER PRIMARY KEY, v TEXT)");
List<Map<String, Object>> rows = e.getExecutor().query("SELECT * FROM t");

// 响应式引擎（同一数据库）
SqliteReactorEngine r = new SqliteReactorEngine();
r.addDataSource("default", "data/app.db");
r.execute("INSERT INTO t(v) VALUES('x')").block();
r.query("SELECT * FROM t").toIterable().forEach(System.out::println);
```

---

## 测试

```bash
mvn test -DskipTests=false "-Dtest=SqliteEnginesTest"
```

测试会创建临时文件数据库，执行 INSERT/SELECT/响应式同步校验，无需外部依赖。

---

## 依赖关系

```
utils-support-sqlite-starter
  ├── utils-support-common-starter         (compile)
  ├── utils-support-datasource-starter     (provided)
  ├── utils-support-native-sqlite          (compile)
  ├── HikariCP                             (compile)
  ├── sqlite-jdbc                          (compile)
  ├── reactor-core                         (compile)  ← 新增，支持 SqliteReactorEngine
  ├── reactor-test                         (test)
  ├── r2dbc-spi                            (test)
  └── junit-jupiter                        (test)
```

---

## Java 25 构建说明

本模块使用 `-J--enable-native-access=ALL-UNNAMED` 编译器参数，以支持 JDK 25 的 foreign function & memory API。构建命令：

```bash
mvn clean package -DskipTests -Dpmd.skip=true -Dmaven.test.skip=true
```
