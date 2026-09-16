# utils-support-rocksdb-starter

RocksDB 嵌入式键值数据库支持模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-rocksdb-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 使用引擎

```java
// SPI 创建引擎
Engine engine = Engine.create("rocksdb");

// 添加本地 RocksDB 数据源
RocksDbEngine rocksDb = (RocksDbEngine) engine;
rocksDb.addDataSource("default", "/path/to/rocksdb-dir");

// 字节 KV 操作
rocksDb.putBytes("default", "key".getBytes(), "value".getBytes());
byte[] val = rocksDb.getBytes("default", "key".getBytes());
rocksDb.deleteBytes("default", "key".getBytes());

// 前缀扫描
List<Map.Entry<byte[], byte[]>> rows = rocksDb.scanBytes("default", "prefix:".getBytes());

// 字符串 KV（KvEngine 契约）
rocksDb.put("user:1", "Alice");
String v = rocksDb.get("user:1");
Map<String, String> all = rocksDb.findAllByPrefix("user:");
long n = rocksDb.incr("counter");

// 原子批量写入
rocksDb.writeBatch("default", List.of(
    new byte[][] {{0}, "k1".getBytes(), "v1".getBytes()},
    new byte[][] {{1}, "k2".getBytes(), null}
));

// 文档存储（DocumentStore 契约）
rocksDb.insert("articles", Map.of("id", "d1", "title", "Hello", "body", "World"));
Map<String, Object> doc = rocksDb.findById("articles", "d1", Map.class);

// 全文检索（FulltextSearch 契约）
List<Map<String, Object>> hits = rocksDb.search("world", Map.class);

// Lambda ORM（基于 RocksDB 真实存储，键布局 ORM:<table>:<id>，实体 JSON 序列化）
// 持久化实体到表 "user"（按 实体 id 或 自增 序号 分 键 写入）
User u1 = new User();
u1.setId(1);
u1.setName("Alice");
u1.setAge(30);
rocksDb.store("user", List.of(u1));

// Lambda 链式查询
List<User> users = rocksDb.query(User.class)
    .eq(User::getAge, 30)
    .orderByDesc(User::getId)
    .list();

// Lambda 分页查询
Page<User> page = rocksDb.query(User.class)
    .page(1, 10);

// Lambda 链式更新（SET age=31 WHERE id=1，原子回写）
rocksDb.update(User.class)
    .set(User::getAge, 31)
    .eq(User::getId, 1)
    .update();

// Lambda 链式删除
rocksDb.delete(User.class)
    .eq(User::getId, 1)
    .remove();

// 关闭
rocksDb.close();
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `RocksDbEngine` | RocksDB 数据库引擎实现，提供字节/字符串 KV、文档存储、全文检索、Lambda ORM 能力。 (SPI: `rocksdb`) |
| `RocksDbEngineDataSource` | RocksDB 数据源封装，持有真实 `RocksDB` 实例与目录路径 |
| `RocksDbOrmStore` | ORM 实体存取辅助类，键布局 `ORM:<table>:<id>`，前缀扫描 + 内存 WHERE 过滤 + 原子批量回写 |

### 核心 API

| 能力 | 说明 |
|------|------|
| 字节 KV | `putBytes` / `getBytes` / `deleteBytes` / `scanBytes` |
| 字符串 KV | `KvEngine` 契约：`get` / `put` / `delete` / `containsKey` / `incr` / `findAllByPrefix` |
| 批量写入 | `writeBatch`（原子 `WriteBatch`） |
| 文档存储 | `DocumentStore` 契约：`insert` / `findById` / `update` / `delete` / `findAll`（JSON 序列化） |
| 全文检索 | `FulltextSearch` 契约：`createFulltextIndex` / `search` / `dropFulltextIndex`（倒排索引） |
| Lambda ORM | `store` / `query(Class)` / `update(Class)` / `delete(Class)`，基于 RocksDB 真实存储（`ORM:<table>:<id>` 键，实体 JSON 序列化，行级原子批量；`id` 优先取实体 `id` 属性，缺省按表自增序号） |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。RocksDB 为嵌入式数据库，数据存储在本地文件目录。

---

## 依赖关系

```
utils-support-rocksdb-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
├── org.rocksdb:rocksdbjni
└── com.fasterxml.jackson.core:jackson-databind
```
