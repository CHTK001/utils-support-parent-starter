# utils-support-qiniu-starter

七牛云集成模块：对象存储 Kodo

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-qiniu-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `QiniuChatClient` | 七牛云对话客户端（桩实现） 七牛云为对象存储服务商，暂未提供 AI 大模型对话能力。 此实现为桩（Stub），调用时返回固定的提示信息。 |
| `QiniuKodoFileStorage` | 七牛云 Kodo 文件存储实现。 基于七牛云 Java SDK 实现 SPI 接口。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-qiniu-starter
├── utils-support-common-starter
```