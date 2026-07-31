# utils-support-tshark-starter

TShark 网络数据包捕获与解析模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-tshark-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `DatabaseManager` | TShark 数据库管理器。 负责初始化 SQLite 数据库、配置 JdbcEngine 数据源、注册实体类并创建表结构。 |
| `CaptureHandler` | 抓包会话 HTTP 处理器。 提供会话列表、开始/暂停/恢复/停止抓包、列出接口、查询状态及导入pcap等接口。 |
| `DataPortHandler` | 数据导入导出 HTTP 处理器。 提供会话及数据包的 JSON 格式导出与导入接口。 |
| `PacketHandler` | 数据包 HTTP 查询处理器。 提供按会话分页查询数据包列表、按ID查询单包详情等接口。 |
| `StatsHandler` | 数据包统计 HTTP 处理器。 提供按会话统计协议分布等接口。 |
| `WebDavBackupHandler` | WebDAV 备份 HTTP 处理器。 提供备份配置、状态查询、执行备份/恢复、管理列表等接口。 |
| `CaptureSession` | 数据包捕获会话实体。 存储一次网络抓包会话的元信息，包括会话名称、网络接口、过滤表达式、状态等。 |
| `PacketCapture` | 网络数据包捕获实体。 存储单条网络数据包的详细信息，包括时间戳、源/目的地址端口、协议、长度及原始JSON等。 |
| `CaptureService` | 抓包会话与数据包的业务管理服务。 |
| `PacketParserService` | TShark JSON数据包解析服务。 |
| ... | 共 13 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-tshark-starter
├── utils-support-common-starter
├── utils-support-datasource-starter
```