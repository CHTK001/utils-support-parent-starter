# utils-support-huawei-starter

华为云集成模块：OBS 存储、盘古大模型对话

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-huawei-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `HuaweiChatClient` | 华为盘古大模型对话客户端 基于华为云盘古大模型 API 的 实现，通过 HTTP 协议 调用华为云 ModelArts 盘古大模型的对话接口。 |
| `HuaweiObsFileStorage` | 华为云 OBS 文件存储实现。 基于华为云 OBS SDK 实现 SPI 接口。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-huawei-starter
├── utils-support-common-starter
```