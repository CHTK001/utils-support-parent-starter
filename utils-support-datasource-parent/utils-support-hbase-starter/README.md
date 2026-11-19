# utils-support-hbase-starter

> utils 体系数据源引擎家族中的 **HBase 引擎实现**：通过 SPI 键 `hbase` 接入统一 `Engine` 体系，暴露真实的 HBase 领域 API（建表 / put / get / scan / deleteRow），基于 ZooKeeper 寻址建立真实连接；不伪装 SQL / ORM。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.hbase.support`（`engine` / `datasource`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-hbase-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `Engine` SPI 的 HBase 实现（SPI 键 `hbase`），连接真实 HBase（ZooKeeper 寻址） | 不是内存模拟，不伪装 ORM |
| API | 暴露 HBase 真实领域 API：`createTable` / `put` / `get` / `scan` / `deleteRow` | 无 SQL、无 Lambda 查询、无 UPDATE/DELETE 语句（显式拒绝） |
| 数据源 | 接受现成 `org.apache.hadoop.hbase.client.Connection` 或 `host:port` quorum 地址串 | 不内建连接池，不管理凭据（无用户名/密码概念） |
| 依赖形态 | 纯 Java 库模块，随 `utils-support-datasource-starter` 的引擎体系按 SPI 装配 | 不依赖 Spring，无自动装配，无 `application.yml` 配置项 |

### 1.3 规模与组成

- Java 源文件 **2** 个：`com.chua.hbase.support.engine.HBaseEngine`、`com.chua.hbase.support.datasource.HBaseEngineDataSource`（均 `@since 4.0.0.42`）。
- SPI 注册文件 1 个：`src/main/resources/META-INF/extensions/com.chua.common.support.lang.datasource.engine.Engine`。
- GraalVM `native-image` 配置 2 个：`reflect-config.json`（注册 `HBaseEngine` 全量反射）、`resource-config.json`（包含 `META-INF/extensions/.*`）。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 获取引擎 | `ServiceProvider.of(Engine.class).getExtension("hbase")` 或 `new HBaseEngine()` | SPI 键 `hbase` |
| 注册数据源（最常用） | `engine.addDataSource("h1", "172.16.0.40:2181")` | ZooKeeper 地址串，缺省端口 `2181` |
| 注册数据源（进阶） | `engine.addDataSource(name, engineDataSource)` | 封装的 source 为 `Connection` 或 quorum 串 |
| 建表 | `engine.createTable("user", "cf")` | 表已存在则跳过 |
| 写一行 | `engine.put(table, rowKey, family, values)` | `values`：列限定符 → 字符串值 |
| 读一行 | `engine.get(table, rowKey, family)` | 行不存在返回空 `Map` |
| 扫描 | `engine.scan(table, family, rowPrefix)` | 每行额外带 `__row` 键 = 行键 |
| 删一行 | `engine.deleteRow(table, rowKey)` | 整行删除 |
| 释放资源 | `engine.close()` | 仅关闭引擎自建的连接 |

### 2.2 HBaseEngine 领域 API

`HBaseEngine extends AbstractEngine`，类上标注 `@Spi("hbase")`；常量 `HBaseEngine.DEFAULT_FAMILY = "cf"` 可作默认列族。

| 方法 | 行为 |
| --- | --- |
| `addDataSource(String name, EngineDataSource<T> dataSource)` | source 为 `Connection` → 直接包装，**不归本引擎关闭**；source 为 quorum 串 → 引擎建连并负责关闭；其它类型抛 `IllegalArgumentException("HBaseEngine 仅支持 Connection 或 quorum 地址串")` |
| `addDataSource(String name, String quorum)` | 便捷重载：由引擎创建连接并负责关闭；`quorum` 形如 `172.16.0.40:2181` |
| `createTable(String table, String family)` | 建表（已存在则跳过） |
| `put(String table, String rowKey, String family, Map<String, String> values)` | 写入一行；相同行键再次 put 即覆盖写 |
| `get(String table, String rowKey, String family)` | 读取一行，返回 列限定符 → 字符串值；行不存在返回空 `Map` |
| `scan(String table, String family, String rowPrefix)` | 全表扫描，可选行键前缀过滤；`family` 为空表示全部列族 |
| `deleteRow(String table, String rowKey)` | 删除一行 |
| `close()` | 关闭所有由本引擎自建的连接（外部传入的连接不关闭），并清空数据源 |

另继承 `AbstractEngine` 的通用数据源管理方法：`setDefaultDataSourceName` / `getDataSource` / `removeDataSource` / `dataSourceNames` / `isClosed` 等。

### 2.3 行为契约（与源码注释一致）

- **连接解析**：领域 API 执行前先取默认数据源连接。未配置数据源、已关闭、多个数据源但未指定默认，均抛 `IllegalStateException`；仅注册一个数据源时无需指定默认。用法错误**不会**被包装成「HBase put/get/scan 失败」。
- **异常语义**：连接失败抛 `IllegalStateException("HBase 连接失败: <quorum>")`；操作失败抛 `IllegalStateException("HBase put/get/scan/delete/建表 失败: <table>[/<rowKey>]")`。
- **scan 结果键**：指定 `family` 时键为列限定符；`family` 传空时取全部列族，键为 `列族:限定符`；每行额外放入 `__row` = 行键。前缀过滤为纯字节运算（末字节 +1 截断），支持非 ASCII 前缀。
- **连接参数**：引擎由 quorum 串内部设置 `hbase.zookeeper.quorum` 与 `hbase.zookeeper.property.clientPort` 两个键后经 `ConnectionFactory.createConnection(conf)` 建连。

### 2.4 显式拒绝的接口（无 SQL 语义）

| 调用 | 结果 |
| --- | --- |
| `store(name, data)` | `UnsupportedOperationException`：HBaseEngine 不支持内存存储/ORM，请使用 `put()`/`scan()` 等真实领域 API |
| Lambda 查询（`query(...)` 执行链） | `UnsupportedOperationException`：HBase 无 SQL 查询，请使用 `scan(table, family, rowPrefix)` |
| `executeUpdate(sql)` | `UnsupportedOperationException`：HBase 无 UPDATE，覆盖写使用 `put()` 相同行键即可 |
| `executeDelete(sql)` | `UnsupportedOperationException`：HBase 无 SQL DELETE，请使用 `deleteRow(table, rowKey)` |

### 2.5 HBaseEngineDataSource

`HBaseEngineDataSource implements EngineDataSource<Connection>`，持有真实 HBase `Connection`（ZooKeeper 寻址）：

| 方法 | 行为 |
| --- | --- |
| `name()` / `url()` | 数据源名称 / 连接描述（quorum 串） |
| `getSource()` | 返回底层 `org.apache.hadoop.hbase.client.Connection` |
| `setSource(Object)` | 不支持运行期替换连接，抛 `UnsupportedOperationException`（请重新 `addDataSource` 注册新数据源） |
| `getDialect()` / `setDialect(...)` | 非 SQL 方言：`getDialect()` 返回 `null`，`setDialect()` 忽略 |
| `username()` / `password()` | 无用户名/密码概念，返回 `null` |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-hbase-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.hbase.support.engine.HBaseEngine;

import java.util.List;
import java.util.Map;

public class HBaseDemo {

    public static void main(String[] args) {
        // 1. 取引擎：SPI 键 "hbase"（也可直接 new HBaseEngine()）
        HBaseEngine engine = (HBaseEngine) ServiceProvider.of(Engine.class).getExtension("hbase");

        // 2. 注册数据源：ZooKeeper 地址串，缺省端口 2181；由引擎建连并负责关闭
        engine.addDataSource("h1", "172.16.0.40:2181");

        // 3. 建表（已存在则跳过）；DEFAULT_FAMILY = "cf"
        engine.createTable("user", HBaseEngine.DEFAULT_FAMILY);

        // 4. 写一行：列限定符 -> 字符串值
        engine.put("user", "row1", "cf", Map.of("name", "张三", "age", "25"));

        // 5. 读一行：行不存在返回空 Map
        Map<String, String> row = engine.get("user", "row1", "cf");
        System.out.println(row);

        // 6. 前缀扫描：结果每行带 __row 键 = 行键
        List<Map<String, String>> rows = engine.scan("user", "cf", "row");
        rows.forEach(r -> System.out.println(r.get("__row") + " -> " + r));

        // 7. 删一行
        engine.deleteRow("user", "row1");

        // 8. 释放：仅关闭引擎自建的连接
        engine.close();
    }
}
```

> 已有现成连接时，可改为注册 `Connection`：构造 `EngineDataSource` 封装后调用 `addDataSource(name, dataSource)`，此时连接的生命周期由调用方负责（`close()` 不会关闭它）。

---

## 4. 配置项

**无。** 依据：

- 本模块资源目录仅有 SPI 注册文件与 native-image 配置，**没有** `application*.yml` / `*.properties` 配置文件；
- 源码中没有 `@ConfigurationProperties` / `@Value` 等配置绑定；
- 连接参数不走路配置，直接通过 `addDataSource(name, quorum)` 的 quorum 串传入；引擎内部仅设置 `hbase.zookeeper.quorum` 与 `hbase.zookeeper.property.clientPort` 两个 Hadoop `Configuration` 键，无其他可配置键。

---

## 5. SPI 扩展点

**本模块是 `Engine` SPI 的实现方**，注册方式与仓库统一机制一致：

- 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.lang.datasource.engine.Engine`
- 注册内容：

```text
hbase=com.chua.hbase.support.engine.HBaseEngine
```

- 实现类上同时标注 `@Spi("hbase")`。

消费方获取引擎：

```java
Engine engine = ServiceProvider.of(Engine.class).getExtension("hbase");
```

本模块**未定义**新的 SPI 扩展接口（没有提供给第三方实现的新接口）；若要扩展引擎家族，参考 `utils-support-common-starter` 的 SPI 机制新增实现并登记到对应扩展文件。

---

## 6. 构建与依赖

### 6.1 依赖清单（pom.xml 实测）

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `org.apache.hbase:hbase-client` | `2.5.12` | HBase 客户端 |
| `org.apache.hadoop:hadoop-common` | `3.5.0` | **覆盖** hbase-client 传递的 3.4.1：JDK 25 移除 SecurityManager 后，3.4.1 的 `UGI.getCurrentUser` 调用 `Subject.getSubject` 必抛 `UnsupportedOperationException` |
| `com.chua:utils-support-common-starter` | 由父 POM 管理 | 契约与工具层 |
| `com.chua:utils-support-datasource-starter` | 由父 POM 管理，`provided` | 引擎抽象基类 `AbstractEngine` |

父模块链：`utils-support-hbase-starter` → `utils-support-datasource-parent`（`4.0.0.51`）→ `utils-support-parent-starter`。

### 6.2 构建

```bash
# 编译校验（仓库交付闸门口径）
mvn -o -q compile -DskipTests

# 打包 / 安装到本地仓库
mvn clean package -DskipTests
mvn clean install -DskipTests
```

测试 JVM 说明：本模块 surefire 配置了 `forkedProcessExitTimeoutInSeconds=15` 与 `surefire.timeout=120`，原因是 HBase 客户端库存在非守护线程，测试完成后需强制回收 fork 进程。

---

## 7. 边界：这些能力不在本模块

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| SQL / Lambda ORM 查询 | 不支持 | `store` / Lambda 查询 / `executeUpdate` / `executeDelete` 均显式抛 `UnsupportedOperationException` |
| 连接池 | 未提供 | 数据源封装仅持有单个 `Connection`，无池化、无多连接复用管理 |
| 运行期替换连接 | 不支持 | `HBaseEngineDataSource.setSource(...)` 抛 `UnsupportedOperationException`，需重新 `addDataSource` |
| 用户名 / 密码 / 安全认证 | 未提供 | `username()` / `password()` 返回 `null`；连接仅设置 ZooKeeper quorum 与端口，无 SASL/Kerberos 等认证配置入口 |
| 非 String 值写入 | 未提供 | `put(...)` 的值为 `Map<String, String>`，按字符串字节写入 |
| 扫描分页 / 过滤下推 | 未提供 | `scan` 仅支持列族裁剪与行键前缀过滤，无分页、无条件过滤 |
| 外部连接的生命周期 | 不归本模块 | `close()` 只关闭引擎自建连接，外部传入的 `Connection` 由调用方关闭 |
| HBase 管理/运维能力 | 不在范围 | 本模块只提供客户端侧建表与数据读写，不做集群管理、Region 运维等 |
