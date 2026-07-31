# utils-support-baidu-starter

百度云集成模块：对象存储 BOS、文心一言大模型

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-baidu-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `BaiduChatClient` | 百度文心一言大模型对话客户端 基于百度千帆大模型平台 API 的 实现，通过 HTTP 协议 调用文心一言（ERNIE-Bot）的对话接口。 |
| `BaiduImageClient` | 百度文心一格图片生成客户端 基于百度文心一格 API 的 实现，通过 HTTP 协议 调用文心一格（ERNIE-ViLG）系列模型的图片生成接口。 |
| `BaiduSmsPush` | 百度云短信推送实现 基于百度云 SMS HTTP API 的短信发送实现。 |
| `BaiduBosFileStorage` | 百度云 BOS 文件存储实现。 基于百度云 BOS SDK 实现 SPI 接口。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-baidu-starter
├── utils-support-common-starter
```