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
| 字节 KV | `putBytes` / `getBytes` / `deleteBytes` / `scanBytes`（真实 `RocksDB.put/get/delete`，前缀扫描走 `RocksIterator`） |
| 字符串 KV | `KvEngine` 契约：`get` / `put` / `delete` / `containsKey` / `incr` / `findAllByPrefix`，真实 写 入 RocksDB `SKV:` 键 空间（重启 后 仍 可 读 回） |
| 批量写入 | `writeBatch`（原子 `WriteBatch`） |
| 文档存储 | `DocumentStore` 契约：`insert` / `findById` / `update` / `delete` / `findAll`（JSON 序列化） |
| 全文检索 | `FulltextSearch` 契约：`createFulltextIndex` / `search` / `dropFulltextIndex`（倒排索引） |
| Lambda ORM | `store` / `query(Class)` / `update(Class)` / `delete(Class)`，基于 RocksDB 真实存储（`ORM:<table>:<id>` 键，实体 JSON 序列化，行级原子批量；`id` 优先取实体 `id` 属性，缺省按表自增序号） |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。RocksDB 为嵌入式数据库，数据存储在本地文件目录。

## 并发语义

- 单 个 `RocksDB` 实例 本身 线程 安全（RocksDB 官方 契约）
- 复合 操作 通过 分级 锁 串行化：
  - **键 级 锁**：字符串 KV `incr` 读-改-写（`SKV:` 键 空间）
  - **集合 级 锁**：文档 + FTS 倒排 条目 的 读-改-写（`DOC:` / `FTS_` 键 空间）
  - **表 级 锁**：ORM 自增 序号 分配 与 读-改-写 循环（`ORM:` 键 空间，跨 调用 共享）
- FTS 采用 **单 值 键 布局**（`FTS_<collection>:<token>:<docId>` → 文档 键），无 多 值 逗号 拼接 竞态；
  文档 与 索引 条目 共 用 同一 `WriteBatch` 原子 写 入，崩溃 不 产生 孤 文档
- 自增 序号 键 后缀 为 **8 位 零 填充 十 进制**（字典 序 = 数值 序，行 键 扫描 顺序 即 插入 顺序）
- 全 表 扫描 后 内存 分页（`supportsNativePaging() = false`）：`limit` 不 省 扫描 I/O，
  大 表 高频 分页 场景 建议 加 二级 索引 或 切 SQL 引擎

---

## 依赖关系

```
utils-support-rocksdb-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
├── org.rocksdb:rocksdbjni
└── com.fasterxml.jackson.core:jackson-databind
```
