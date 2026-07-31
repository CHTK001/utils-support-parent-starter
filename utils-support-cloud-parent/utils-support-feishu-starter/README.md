# utils-support-feishu-starter

飞书开放平台 Bot 客户端集成

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.larksuite.oapi</groupId>
    <artifactId>utils-support-feishu-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `FeishuBotClient` | 飞书 Bot 客户端，实现 接口。 |
| `FeishuBotClientFactory` | 飞书 Bot 客户端工厂（SPI 实现）。 平台名称为 。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-feishu-starter
├── utils-support-common-starter
```