# utils-support-mysql-starter

MySQL 数据库支持：用户管理、元数据管理、阻塞与响应式双引擎。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mysql-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `MysqlEngine` | MySQL 阻塞引擎。基于 HikariCP 连接池（默认最大连接 10），提供 MySQL 专有的表结构、数据源配置方法 (SPI: `mysql`) |
| `MysqlReactorEngine` | MySQL 响应式引擎，extends JdbcReactorEngine。经 `boundedElastic` 调度在 JDBC DataSource 上执行，对外暴露 Flux/Mono (SPI: 继承注册路径) |
| `MysqlMetaData` / `MysqlMetaTable` 等 | 表、视图、列、索引、外键、存储过程、触发器元数据操作 |

> 其余 DDL 步骤类（`MysqlCreateIndexStep` 等）用于索引管理与 DSL 构建。

---

## 使用示例

```java
// 阻塞引擎
MysqlEngine engine = new MysqlEngine();
engine.addDataSource("m", "172.16.0.40", 3306, "mydb", "root", "password");
List<Map<String,Object>> rows = engine.getExecutor()
        .query("SELECT * FROM t WHERE id = ?", 1);

// 响应式引擎（同一数据库）
MysqlReactorEngine reactor = new MysqlReactorEngine();
reactor.addDataSource("r", "172.16.0.40", 3306, "mydb", "root", "password");
reactor.query("SELECT id, name FROM t")
        .doOnNext(row -> System.out.println(row))
        .subscribe();
```

> 注意：响应式引擎内部经 `registerJdbcDataSource` 注册纯 JDBC 数据源，
> 在 `boundedElastic` 上执行真实 JDBC 调用。

---

## 测试

```bash
mvn test -DskipTests=false "-Dtest=MysqlEnginesTest"
```

- `MysqlEnginesTest` 为**真实服务集成测试**（默认目标 172.16.0.40:3308 / testdb），
  环境不可达时自动跳过。
- 远程账号需允许来源 IP 访问；容器化 MySQL 可用：
  `CREATE USER 'it'@'%' IDENTIFIED BY '...'; GRANT ALL ON testdb.* TO 'it'@'%';`
- 已知外部遗留：`MysqlUserManagerIT` 凭据过期（root@），仅编译校验不参与执行。

---

## 依赖关系

```
utils-support-mysql-starter
├── utils-support-datasource-starter (provided)
├── HikariCP
└── mysql-connector-j (runtime)
```
