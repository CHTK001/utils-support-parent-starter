# Cloudflare Starter

Cloudflare 集成模块，提供 **D1 SQLite Engine 客户端**，通过 Cloudflare API v4 REST 访问 D1 数据库。

## 模块概览

| 类 | 职责 |
|---|---|
| `CloudflareConfig` | 配置（token / accountId / databaseId） |
| `CloudflareClient` | API v4 通用 HTTP 客户端（Bear Token + 错误解析） |
| `CloudflareD1Engine` | D1 SQLite 引擎：execute / query / batch / 命名参数 |
| `D1Statement` | 单条 SQL + 参数 |
| `D1SqlParameter` | 参数绑定（`?` 占位符与 `:name` 命名参数） |
| `D1QueryRequest` / `D1BatchRequest` | 请求体序列化 |
| `D1Result` | 响应映射（meta + 列名 Map 列表） |

## 快速上手

```java
CloudflareConfig config = new CloudflareConfig()
        .setToken(System.getenv("CF_TOKEN"))
        .setAccountId(System.getenv("CF_ACCOUNT_ID"))
        .setDatabaseId(System.getenv("CF_DB_ID"));

CloudflareClient client = new CloudflareClient(config);
CloudflareD1Engine engine = new CloudflareD1Engine(client);

// 建表
engine.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)");

// 插入（位置参数）
engine.execute("INSERT INTO users(name) VALUES(?)", "Alice");

// 查询
List<Map<String, Object>> rows = engine.query("SELECT * FROM users WHERE id = ?", 1);

// 命名参数
engine.execute(
        "UPDATE users SET name = :name WHERE id = :id",
        Map.of("name", "Bob", "id", 1));

// 批量
engine.batch(List.of(
        D1Statement.of("INSERT INTO users(name) VALUES(?)", "Carol"),
        D1Statement.of("INSERT INTO users(name) VALUES(?)", "Dave")
));
```

## D1 API 协议

本客户端遵循 Cloudflare 官方文档：
- 路径：`POST /accounts/{account_id}/d1/database/{database_id}/query`
- 请求体：`{"sql": "...", "params": [...] | {...name: value...}, "return_metadata": true}`
- 响应：`{"success": true, "result": {"meta": {...}, "rows": [{"columns": [...], "values": [...]}]}}`