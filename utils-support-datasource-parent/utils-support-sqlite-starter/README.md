# utils-support-sqlite-starter

SQLite 嵌入式数据库支持：阻塞引擎 + 响应式引擎（JDBC 路径）+ 方言。

---

## 快速开始

### 1. 添加依赖

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
| `SqliteEngine` | SQLite 阻塞引擎，基于 HikariCP + sqlite-jdbc (SPI: `sqlite`) |
| `SqliteReactorEngine` | SQLite 响应式引擎：`boundedElastic` 调度 JDBC 调用，对外暴露 Flux/Mono。内部委托同步引擎并注册纯 JDBC 数据源（SQLite 无 R2DBC 驱动） |
| `SqlitePolledDirectory` | 数据库轮询目录实现 |

---

## 使用示例

```java
// 阻塞
SqliteEngine e = new SqliteEngine();
e.addDataSource("default", "data/app.db");
e.getExecutor().execute("CREATE TABLE IF NOT EXISTS t(id INTEGER PRIMARY KEY, v TEXT)");

// 响应式（同一文件库）
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

本地临时文件闭环：建表→插入→查询→响应式同库校验，无需外部服务。

---

## 依赖关系

```
utils-support-sqlite-starter
├── utils-support-common-starter
├── utils-support-datasource-starter (provided)
├── HikariCP
└── sqlite-jdbc
```
