# utils-support-mongodb-starter

基于 MongoDB 官方同步驱动实现管理面 SPI `MongoManageProvider`，提供连接探测、逻辑库与集合浏览、只读 Shell 查询及按 `_id` 查看文档。契约位于 `utils-support-datasource-starter`，本模块提供驱动实现，不依赖 Spring，不维护宿主的数据源台账。

## 安装

JDK 25，版本与本仓库保持一致：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mongodb-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

依赖传递包含 datasource/common 契约与 `mongodb-driver-sync`。实现由 `META-INF/extensions` 登记为 `mongodb`。

## 连接与浏览

以下代码放在方法体中，环境变量由部署方提供；示例不会打印连接串或凭据。

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.datasource.support.middleware.mongodb.MongoManageProvider;
import com.chua.datasource.support.middleware.mongodb.MongoManageTarget;

MongoManageProvider provider = ServiceProvider.of(MongoManageProvider.class)
        .getExtension(MongoManageProvider.SPI_NAME);
if (provider == null) {
    throw new IllegalStateException("MongoDB 实现未安装");
}
MongoManageTarget target = new MongoManageTarget(
        0L, "业务数据源", System.getenv("MONGO_HOST"), 27017,
        "admin", System.getenv("MONGO_USER"), System.getenv("MONGO_PASSWORD"), "");
var status = provider.test(target);
if (!status.connected()) {
    throw new IllegalStateException(status.message());
}
var databases = provider.databases(target);
var collections = provider.collections(target, "app");
```

- `test` 执行 `ping`，再尝试读取 `buildInfo`；无权读取版本时版本字符串可为空。
- 端口非正数时使用 27017；默认库为空时使用 `admin`。目标的默认库同时是认证库，`collections/query/document` 的 `database` 参数控制实际读取的业务库。
- 用户名为空时不带认证。用户名和口令在生成连接串时编码；当前目标不是任意 MongoDB URI 配置入口，不提供独立的副本集、TLS、SRV 参数。
- `versionToken` 当前不参与连接缓存键。实现按完整连接串复用 `MongoClient`；配置变化后旧客户端仍保留在当前实例中，没有显式关闭/逐出 API。

## 查询与文档查看

```java
var rows = provider.query(target, "app",
        "db.orders.find({status: \"PAID\"}, {status: 1, amount: 1})"
        + ".sort({amount: -1}).skip(0).limit(20)");
var count = provider.query(target, "app", "db.orders.countDocuments({})");
var grouped = provider.query(target, "app",
        "db.orders.aggregate([{$match:{status:\"PAID\"}},"
        + "{$group:{_id:\"$status\",total:{$sum:1}}}])");
var document = provider.document(target, "app", "orders", "order-001");
System.out.println(rows.rows());
```

支持 `find`、`findOne`、`count`、`countDocuments`、`aggregate`；find 支持投影、`sort`、`skip` 与 `limit`。`MongoShell` 是有限语法解析器，不是 JavaScript/mongosh 执行环境。支持裸键、`ObjectId("…")` 与 `ISODate("…")` 的转换，复杂表达式以其解析规则为准。

未写 `limit` 的 find 默认取 50 行；显式 limit 和聚合最多返回 500 行。`MongoQueryResult` 的 `rows/limited/totalRows` 用于显示结果与截断提示，`totalRows` 不是所有查询的精确总数：显式 limit 和聚合主要表示返回行数。计数查询返回一行 `{count: ...}`。

`document` 先把 ID 解析为 ObjectId，解析失败才按字符串匹配；不存在返回 `null`，不支持任意类型 `_id` 的自动推断。ObjectId、日期、二进制分别映射成十六进制、ISO 时间和 Base64；长字符串按 4000 字符截断，结果适合管理面查看，不适合作无损导出。

## 运行边界

- 不提供插入、更新、删除或建库建集合入口；聚合拒绝 `$out`、`$merge`。建议连接账号本身只授予所需读取权限。
- 选主和连接超时为 5 秒，读取超时为 15 秒；网络、权限、认证仍由真实服务决定。
- 查询语法错误抛 `IllegalArgumentException`，驱动操作错误通常翻译为带操作上下文的 `IllegalStateException`。调用方应处理失败，不把空列表当作连接成功。
- 本 README 根据源码核对，不代表已连接某个 MongoDB 服务完成运行验收。

返回 [中间件目录](../README.md) / [完整目录](../../README.md)。
