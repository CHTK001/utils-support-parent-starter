# utils-support-enhance-starter

增强工具模块：emoji、常用增强功能

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-enhance-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `EnhanceBackupStrategyProvider` | 增强版按天备份策略 通过 SPI 注册，扩展 ， 增加日志记录、备份校验和更灵活的目录结构支持。 (SPI: `daily`) |
| `AbstractEmoji` | Emoji 抽象基类，提供 ShortCode 和 HTML 实体的正则模式以及 HTML 转换辅助方法。 Chaitanya Thota |
| `Emoji` | Emoji 数据模型，封装单个 emoji 的 Unicode 编码、别名、HTML 实体和表情符号等信息。 |
| `EmojiManager` | Emoji 管理器，负责 emoji 数据的注册、检索和管理。 |
| `EmojiTrie` | Emoji Trie 树，基于前缀树（Trie）结构高效匹配和检索 emoji 字符。 |
| `EmojiUtils` | Emoji 工具类，提供 emoji 的查找、解析、转换和统计功能。 |
| `ClashSubscriptionParser` | ClashSubscriptionParser (SPI: `clash`) |
| `QualityProxyPool` | QualityProxyPool (SPI: `quality`) |
| `SubscriptionProxyFetcher` | SubscriptionProxyFetcher (SPI: `subscription`) |
| `AuthServerFilter` | 认证过滤器，验证请求是否携带有效的认证凭证。 |
| ... | 共 19 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-enhance-starter
├── utils-support-common-starter
```